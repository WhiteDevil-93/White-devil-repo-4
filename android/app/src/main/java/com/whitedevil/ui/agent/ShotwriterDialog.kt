package com.whitedevil.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.whitedevil.agent.ShotwriterCatalog
import com.whitedevil.agent.VideoModel
import com.whitedevil.ui.theme.WdPalette

@Composable
fun ShotwriterDialog(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onRewrite: (model: VideoModel, resolution: String, ratio: String, length: String) -> Unit
) {
    if (!expanded) return

    var selectedModel by remember { mutableStateOf(ShotwriterCatalog.models.first()) }
    var selectedResolution by remember(selectedModel) { mutableStateOf(selectedModel.supportedResolutions.firstOrNull() ?: "") }
    var selectedRatio by remember(selectedModel) { mutableStateOf(selectedModel.supportedRatios.firstOrNull() ?: "") }
    var selectedLength by remember(selectedModel) { mutableStateOf(selectedModel.supportedLengths.firstOrNull() ?: "") }

    var showingModelList by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .clip(RoundedCornerShape(12.dp))
                .background(WdPalette.bg)
                .border(1.dp, WdPalette.stroke, RoundedCornerShape(12.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(WdPalette.surface)
                        .border(1.dp, WdPalette.stroke, RoundedCornerShape(8.dp))
                        .clickable { showingModelList = !showingModelList }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(selectedModel.name, color = WdPalette.text, fontSize = 14.sp)
                }
                
                Spacer(Modifier.weight(1f))
                
                Button(
                    onClick = { 
                        onRewrite(selectedModel, selectedResolution, selectedRatio, selectedLength)
                        onDismissRequest() 
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WdPalette.surfaceHover, contentColor = WdPalette.accentLight),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Outlined.AutoAwesome, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Rewrite", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
            
            // Show selected configuration summaries horizontally
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (selectedResolution.isNotEmpty()) {
                    Text(selectedResolution, color = WdPalette.textSecondary, fontSize = 12.sp)
                }
                if (selectedLength.isNotEmpty()) {
                    Text(selectedLength, color = WdPalette.textSecondary, fontSize = 12.sp)
                }
                if (selectedRatio.isNotEmpty()) {
                    Text(selectedRatio, color = WdPalette.textSecondary, fontSize = 12.sp)
                }
            }

            if (showingModelList) {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    item { Text("Featured Models", color = WdPalette.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp)) }
                    items(ShotwriterCatalog.models) { model ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (model.id == selectedModel.id) WdPalette.surface else Color.Transparent)
                                .clickable { 
                                    selectedModel = model 
                                    showingModelList = false
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(model.name, color = WdPalette.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(model.description, color = WdPalette.textSecondary, fontSize = 12.sp)
                            }
                            if (model.id == selectedModel.id) {
                                Icon(Icons.Outlined.Check, null, tint = WdPalette.accent, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Settings for ${selectedModel.name}", color = WdPalette.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    
                    if (selectedModel.supportedResolutions.isNotEmpty()) {
                        Column {
                            Text("Resolution", color = WdPalette.text, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                selectedModel.supportedResolutions.forEach { res ->
                                    val isSelected = res == selectedResolution
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (isSelected) WdPalette.accent else WdPalette.surfaceHover)
                                            .clickable { selectedResolution = res }
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text(res, color = if (isSelected) Color.White else WdPalette.text, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }

                    if (selectedModel.supportedRatios.isNotEmpty()) {
                        Column {
                            Text("Aspect Ratio", color = WdPalette.text, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                selectedModel.supportedRatios.forEach { ratio ->
                                    val isSelected = ratio == selectedRatio
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (isSelected) WdPalette.accent else WdPalette.surfaceHover)
                                            .clickable { selectedRatio = ratio }
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text(ratio, color = if (isSelected) Color.White else WdPalette.text, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }

                    if (selectedModel.supportedLengths.isNotEmpty()) {
                        Column {
                            Text("Length", color = WdPalette.text, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                selectedModel.supportedLengths.forEach { len ->
                                    val isSelected = len == selectedLength
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (isSelected) WdPalette.accent else WdPalette.surfaceHover)
                                            .clickable { selectedLength = len }
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text(len, color = if (isSelected) Color.White else WdPalette.text, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
