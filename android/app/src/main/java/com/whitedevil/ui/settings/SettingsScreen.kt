package com.whitedevil.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.whitedevil.*
import com.whitedevil.MainActivity
import com.whitedevil.ui.app.SettingsFormState
import com.whitedevil.ui.components.WdScreenBackground
import com.whitedevil.ui.components.WdSurfaceCard
import com.whitedevil.ui.theme.WdPalette

@Composable
fun SettingsScreen(
    host: MainActivity,
    showBack: Boolean,
    form: SettingsFormState,
    onFormChange: (SettingsFormState) -> Unit,
) {
    WdScreenBackground(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            if (showBack) {
                Text(
                    "← You",
                    style = MaterialTheme.typography.labelMedium,
                    color = WdPalette.accent,
                    modifier = Modifier
                        .padding(bottom = 12.dp)
                        .clickable { host.showYouSub(MainActivity.YouSub.HOME) },
                )
            }
            Text("Settings", style = MaterialTheme.typography.headlineLarge)
            Text(
                "Encrypted on device",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 20.dp),
            )
            WdSurfaceCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Connection health", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(host.connectionSummaryPublic(), style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { host.runQuickConnectionTest(updateYouHome = false, form = form) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = WdPalette.surface, contentColor = WdPalette.text),
            ) { Text("Run connection test") }
            Spacer(Modifier.height(16.dp))
            SettingsSection("Venice") {
                Field("API key", form.veniceKey, secret = true) { onFormChange(form.copy(veniceKey = it)) }
                Field("System prompt", form.systemPrompt, minLines = 4) { onFormChange(form.copy(systemPrompt = it)) }
                RowSwitch("Web search", form.webSearch) { onFormChange(form.copy(webSearch = it)) }
            }
            Spacer(Modifier.height(10.dp))
            SettingsSection("Relay") {
                Field("Base URL", form.relayUrl) { onFormChange(form.copy(relayUrl = it)) }
                Field("User", form.relayUser) { onFormChange(form.copy(relayUser = it)) }
                Field("Password", form.relayPass, secret = true) { onFormChange(form.copy(relayPass = it)) }
            }
            Spacer(Modifier.height(10.dp))
            SettingsSection("Laptop tunnel") {
                Field("User", form.laptopUser) { onFormChange(form.copy(laptopUser = it)) }
                Field("Password", form.laptopPass, secret = true) { onFormChange(form.copy(laptopPass = it)) }
            }
            Button(
                onClick = { host.saveSettingsFromCompose(form) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = WdPalette.accent, contentColor = WdPalette.onAccent),
            ) { Text("Save", fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    WdSurfaceCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    secret: Boolean = false,
    minLines: Int = 1,
    onChange: (String) -> Unit,
) {
    Text(label, style = MaterialTheme.typography.labelSmall)
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        minLines = minLines,
        textStyle = MaterialTheme.typography.bodyMedium,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = WdPalette.stroke,
            unfocusedBorderColor = WdPalette.stroke,
            focusedContainerColor = WdPalette.bgElevated,
            unfocusedContainerColor = WdPalette.bgElevated,
            focusedTextColor = WdPalette.text,
            unfocusedTextColor = WdPalette.text,
        ),
    )
}

@Composable
private fun RowSwitch(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(
                checkedThumbColor = WdPalette.onAccent,
                checkedTrackColor = WdPalette.accent,
            ),
        )
    }
}
