package com.whitedevil.security

import android.app.Activity
import android.util.Log
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CredentialManager
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Offers relay / laptop passwords to the system password manager
 * (Google Password Manager, Samsung Pass, etc.) via Credential Manager.
 */
object PasswordManagerBridge {
    private const val TAG = "WdPasswordMgr"

    suspend fun offerSave(
        activity: Activity,
        id: String,
        password: String,
        origin: String? = null,
    ): Boolean {
        if (id.isBlank() || password.isBlank()) return false
        return withContext(Dispatchers.Main) {
            try {
                val cm = CredentialManager.create(activity)
                val req = if (origin.isNullOrBlank()) {
                    CreatePasswordRequest(id = id, password = password)
                } else {
                    CreatePasswordRequest(id = id, password = password, origin = origin)
                }
                cm.createCredential(activity, req)
                true
            } catch (_: CreateCredentialCancellationException) {
                false
            } catch (e: CreateCredentialException) {
                Log.i(TAG, "Password manager skipped: ${e.type} ${e.message}")
                false
            } catch (e: Exception) {
                Log.w(TAG, "Password manager unavailable", e)
                false
            }
        }
    }

    /** Save relay + laptop credentials when present. Returns how many were offered. */
    suspend fun offerAll(
        activity: Activity,
        relayUrl: String,
        relayUser: String,
        relayPass: String,
        laptopUser: String,
        laptopPass: String,
    ): Int {
        var n = 0
        val origin = relayUrl.trim().trimEnd('/').takeIf {
            it.startsWith("https://") || it.startsWith("http://")
        }
        if (relayUser.isNotBlank() && relayPass.isNotBlank()) {
            if (offerSave(activity, id = "whitedevil-relay:$relayUser", password = relayPass, origin = origin)) {
                n++
            }
        }
        if (laptopUser.isNotBlank() && laptopPass.isNotBlank()) {
            if (offerSave(activity, id = "whitedevil-laptop:$laptopUser", password = laptopPass, origin = origin)) {
                n++
            }
        }
        return n
    }
}
