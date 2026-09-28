package com.whitedevil.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whitedevil.*
import com.whitedevil.MainActivity
import com.whitedevil.ui.app.SettingsFormState
import com.whitedevil.ui.components.WdGlassCard
import com.whitedevil.ui.components.WdScreenBackground
import com.whitedevil.ui.theme.WdColors

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
                    color = WdColors.accent,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .padding(bottom = 12.dp)
                        .clickable { host.showYouSub(MainActivity.YouSub.HOME) },
                )
            }
            Text("WHITEDEVIL", color = WdColors.accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Text("Settings", color = WdColors.strong, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(
                "Credentials are encrypted on-device via Android Jetpack Security.",
                color = WdColors.muted,
                fontSize = 12.5.sp,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            WdGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Connection health", color = WdColors.strong, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(host.connectionSummaryPublic(), color = WdColors.muted, fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { host.runQuickConnectionTest(updateYouHome = false, form = form) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0x331A1A1E), contentColor = WdColors.strong),
            ) { Text("Run connection test") }
            Spacer(Modifier.height(16.dp))
            SettingsSection("Venice AI Agent") {
                Field("Venice API key", form.veniceKey, secret = true) { onFormChange(form.copy(veniceKey = it)) }
                Field("System prompt", form.systemPrompt, minLines = 4) { onFormChange(form.copy(systemPrompt = it)) }
                RowSwitch("Venice web search", form.webSearch) { onFormChange(form.copy(webSearch = it)) }
            }
            SettingsSection("Relay & Forge Hub") {
                Field("Relay base URL", form.relayUrl) { onFormChange(form.copy(relayUrl = it)) }
                Field("Relay user", form.relayUser) { onFormChange(form.copy(relayUser = it)) }
                Field("Relay password", form.relayPass, secret = true) { onFormChange(form.copy(relayPass = it)) }
            }
            SettingsSection("Laptop SSH tunnel") {
                Field("Laptop user", form.laptopUser) { onFormChange(form.copy(laptopUser = it)) }
                Field("Laptop password", form.laptopPass, secret = true) { onFormChange(form.copy(laptopPass = it)) }
            }
            Button(
                onClick = { host.saveSettingsFromCompose(form) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(containerColor = WdColors.accent, contentColor = Color(0xFF111111)),
            ) { Text("Save Settings", fontWeight = FontWeight.Bold, fontSize = 15.sp) }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    WdGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, color = WdColors.strong, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
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
    Text(label, color = WdColors.muted, fontSize = 11.5.sp)
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        minLines = minLines,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
    )
}

@Composable
private fun RowSwitch(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    androidx.compose.foundation.layout.Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = WdColors.strong, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
