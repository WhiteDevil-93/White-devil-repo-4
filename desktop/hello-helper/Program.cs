// WhiteDevil Windows Hello helper.
//
// The desktop app is Kotlin/JVM, and the JVM cannot reach Windows Hello. Rather
// than fight that with FFI, this tiny exe owns the entire native surface: make a
// Hello-protected key, and sign a nonce with it. The private key is created and
// held by Windows (TPM-backed where available) and is unusable without the
// user's PIN or fingerprint — this process never sees it.
//
// KeyCredentialManager issues RSA-2048 and signs PKCS#1 v1.5 over SHA-256, i.e.
// RS256. That is not negotiable and is why hub/auth.py accepts RS256 alongside
// the ES256 that Android StrongBox produces.
//
// Every result is a single line of JSON on stdout so the Kotlin side can parse it
// without scraping text. Failures exit non-zero AND report ok:false, so a caller
// cannot mistake a crash for a refusal.
//
//   wd-hello status [keyName]
//   wd-hello create [keyName]          -> {"ok":true,"public_key_pem":"..."}
//   wd-hello sign   [keyName] <nonce>  -> {"ok":true,"signature_b64":"..."}

using System.Text;
using System.Text.Json;
using Windows.Security.Credentials;
using Windows.Security.Cryptography;
using Windows.Security.Cryptography.Core; // CryptographicPublicKeyBlobType
using Windows.Storage.Streams;

namespace WhiteDevil.Hello;

internal static class Program
{
    private const string DefaultKeyName = "WhiteDevilDeviceKey";

    private static async Task<int> Main(string[] args)
    {
        try
        {
            var cmd = (args.Length > 0 ? args[0] : "status").ToLowerInvariant();
            var keyName = args.Length > 1 && !string.IsNullOrWhiteSpace(args[1]) ? args[1] : DefaultKeyName;

            return cmd switch
            {
                "status" => await StatusAsync(keyName),
                "create" => await CreateAsync(keyName),
                "sign" => await SignAsync(keyName, args.Length > 2 ? args[2] : ""),
                _ => Fail($"unknown command '{cmd}'; expected status, create or sign"),
            };
        }
        catch (Exception e)
        {
            // Never let a raw .NET stack trace reach the caller as if it were data.
            return Fail($"{e.GetType().Name}: {e.Message}");
        }
    }

    private static async Task<int> StatusAsync(string keyName)
    {
        var supported = await KeyCredentialManager.IsSupportedAsync();
        var exists = false;
        if (supported)
        {
            // OpenAsync does NOT prompt; it only reports whether the key is there.
            var open = await KeyCredentialManager.OpenAsync(keyName);
            exists = open.Status == KeyCredentialStatus.Success;
        }
        return Ok(new Dictionary<string, object?>
        {
            ["hello_available"] = supported,
            ["key_exists"] = exists,
            ["key_name"] = keyName,
            ["detail"] = supported
                ? (exists ? "Ready." : "Windows Hello is set up; no WhiteDevil key yet — run create.")
                : "Windows Hello is not set up on this account. Add a PIN or fingerprint in Windows Settings > Accounts > Sign-in options.",
        });
    }

    private static async Task<int> CreateAsync(string keyName)
    {
        if (!await KeyCredentialManager.IsSupportedAsync())
            return Fail("Windows Hello is not set up on this account; add a PIN or fingerprint first.");

        // ReplaceExisting so re-enrolling is possible after a revoke. The old key
        // becomes useless the moment the hub forgets its public half.
        var result = await KeyCredentialManager.RequestCreateAsync(keyName, KeyCredentialCreationOption.ReplaceExisting);
        if (result.Status != KeyCredentialStatus.Success)
            return Fail($"Windows would not create the key: {Describe(result.Status)}");

        // X.509 SubjectPublicKeyInfo is exactly the DER the hub's PEM loader wants.
        var spki = result.Credential.RetrievePublicKey(CryptographicPublicKeyBlobType.X509SubjectPublicKeyInfo);
        return Ok(new Dictionary<string, object?>
        {
            ["key_name"] = keyName,
            ["algorithm"] = "RS256",
            ["public_key_pem"] = ToPem(ToBytes(spki)),
        });
    }

    private static async Task<int> SignAsync(string keyName, string nonce)
    {
        if (string.IsNullOrEmpty(nonce))
            return Fail("sign needs a nonce: wd-hello sign <keyName> <nonce>");

        var open = await KeyCredentialManager.OpenAsync(keyName);
        if (open.Status != KeyCredentialStatus.Success)
            return Fail($"No Hello key named '{keyName}' on this account ({Describe(open.Status)}); run create first.");

        // Sign the RAW UTF-8 BYTES of the nonce — the hub verifies against exactly
        // these bytes, so any re-encoding here breaks verification.
        var buffer = CryptographicBuffer.CreateFromByteArray(Encoding.UTF8.GetBytes(nonce));

        // This is the call that prompts for PIN/fingerprint. A user who cancels
        // gets UserCanceled, which is a refusal, not an error to retry blindly.
        var signed = await open.Credential.RequestSignAsync(buffer);
        if (signed.Status != KeyCredentialStatus.Success)
            return Fail($"Signing was not completed: {Describe(signed.Status)}");

        return Ok(new Dictionary<string, object?>
        {
            ["key_name"] = keyName,
            ["algorithm"] = "RS256",
            ["signature_b64"] = Convert.ToBase64String(ToBytes(signed.Result)),
        });
    }

    private static byte[] ToBytes(IBuffer buffer)
    {
        CryptographicBuffer.CopyToByteArray(buffer, out var bytes);
        return bytes;
    }

    /// <summary>DER SubjectPublicKeyInfo -> PEM, wrapped at 64 chars as the format expects.</summary>
    private static string ToPem(byte[] der)
    {
        var b64 = Convert.ToBase64String(der);
        var sb = new StringBuilder("-----BEGIN PUBLIC KEY-----\n");
        for (var i = 0; i < b64.Length; i += 64)
            sb.Append(b64, i, Math.Min(64, b64.Length - i)).Append('\n');
        return sb.Append("-----END PUBLIC KEY-----\n").ToString();
    }

    /// <summary>Plain-language status, so the caller shows a reason rather than an enum name.</summary>
    private static string Describe(KeyCredentialStatus status) => status switch
    {
        KeyCredentialStatus.Success => "success",
        KeyCredentialStatus.UserCanceled => "you cancelled the Windows Hello prompt",
        KeyCredentialStatus.NotFound => "no such key for this Windows account",
        KeyCredentialStatus.UserPrefersPassword => "this account prefers a password; Hello is not enabled",
        KeyCredentialStatus.CredentialAlreadyExists => "a key with that name already exists",
        KeyCredentialStatus.SecurityDeviceLocked => "the security device is locked — sign in to Windows again",
        KeyCredentialStatus.UnknownError => "Windows reported an unknown error",
        _ => status.ToString(),
    };

    private static int Ok(Dictionary<string, object?> payload)
    {
        payload["ok"] = true;
        Console.Out.WriteLine(JsonSerializer.Serialize(payload));
        return 0;
    }

    private static int Fail(string message)
    {
        Console.Out.WriteLine(JsonSerializer.Serialize(new Dictionary<string, object?>
        {
            ["ok"] = false,
            ["error"] = message,
        }));
        return 1;
    }
}
