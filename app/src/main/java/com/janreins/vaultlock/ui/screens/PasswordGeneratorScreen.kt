package com.janreins.vaultlock.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.janreins.vaultlock.generator.GeneratorOptions
import com.janreins.vaultlock.generator.PasswordGenerator
import com.janreins.vaultlock.ui.VaultViewModel
import com.janreins.vaultlock.ui.theme.Amber400
import com.janreins.vaultlock.ui.theme.Amber500
import com.janreins.vaultlock.ui.theme.EmeraldSuccess

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordGeneratorScreen(
    viewModel: VaultViewModel,
    onNavigateBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Password Generator",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("generator_back_btn")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            PasswordGeneratorContent(
                viewModel = viewModel,
                onUsePassword = null,
                onClose = onNavigateBack
            )
        }
    }
}

@Composable
fun PasswordGeneratorContent(
    viewModel: VaultViewModel,
    onUsePassword: ((String) -> Unit)?,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    var options by remember { mutableStateOf(uiState.generatorOptions) }
    var sliderValue by remember { mutableFloatStateOf(options.length.toFloat()) }

    val strength = PasswordGenerator.evaluateStrength(uiState.currentGeneratedPassword)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
            .navigationBarsPadding()
    ) {
        // Main Password Display Box
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Generated Password Text Box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = uiState.currentGeneratedPassword,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            letterSpacing = 1.sp
                        ),
                        color = Amber400,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Strength Meter
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Security: ${strength.label}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(strength.colorHex)
                    )
                    Text(
                        text = "${uiState.currentGeneratedPassword.length} characters",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                LinearProgressIndicator(
                    progress = { strength.score },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = Color(strength.colorHex),
                    trackColor = MaterialTheme.colorScheme.surface
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Action Row: Copy & Regenerate
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            viewModel.copyToClipboard(
                                context,
                                uiState.currentGeneratedPassword,
                                "Generated Password"
                            )
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        shape = RoundedCornerShape(12.dp),
                        enabled = uiState.currentGeneratedPassword.isNotEmpty(),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("gen_copy_btn")
                    ) {
                        Icon(
                            imageVector = if (uiState.activeCopiedLabel == "Generated Password") Icons.Default.Check else Icons.Default.ContentCopy,
                            contentDescription = null,
                            tint = if (uiState.activeCopiedLabel == "Generated Password") EmeraldSuccess else Amber400,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (uiState.activeCopiedLabel == "Generated Password") "Copied!" else "Copy")
                    }

                    Button(
                        onClick = { viewModel.regeneratePassword() },
                        colors = ButtonDefaults.buttonColors(containerColor = Amber500),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("gen_refresh_btn")
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            tint = Color(0xFF0F172A),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Regenerate", color = Color(0xFF0F172A), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Length Slider
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Password Length",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${sliderValue.toInt()}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Amber400
                    )
                }

                Slider(
                    value = sliderValue,
                    onValueChange = {
                        sliderValue = it
                        val newOpts = options.copy(length = it.toInt())
                        options = newOpts
                        viewModel.updateGeneratorOptions(newOpts)
                    },
                    valueRange = 8f..64f,
                    steps = 55,
                    colors = SliderDefaults.colors(
                        thumbColor = Amber400,
                        activeTrackColor = Amber500,
                        inactiveTrackColor = MaterialTheme.colorScheme.surface
                    ),
                    modifier = Modifier.testTag("password_length_slider")
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Preset style chips
        Text(
            text = "CHARACTER SETS",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Amber400,
            modifier = Modifier.padding(bottom = 6.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                GeneratorOptionToggle(
                    title = "Uppercase Letters (A-Z)",
                    checked = options.includeUppercase,
                    onCheckedChange = {
                        val newOpts = options.copy(includeUppercase = it)
                        options = newOpts
                        viewModel.updateGeneratorOptions(newOpts)
                    }
                )
                GeneratorOptionToggle(
                    title = "Lowercase Letters (a-z)",
                    checked = options.includeLowercase,
                    onCheckedChange = {
                        val newOpts = options.copy(includeLowercase = it)
                        options = newOpts
                        viewModel.updateGeneratorOptions(newOpts)
                    }
                )
                GeneratorOptionToggle(
                    title = "Numbers (0-9)",
                    checked = options.includeNumbers,
                    onCheckedChange = {
                        val newOpts = options.copy(includeNumbers = it)
                        options = newOpts
                        viewModel.updateGeneratorOptions(newOpts)
                    }
                )
                GeneratorOptionToggle(
                    title = "Special Symbols (!@#$%)",
                    checked = options.includeSymbols,
                    onCheckedChange = {
                        val newOpts = options.copy(includeSymbols = it)
                        options = newOpts
                        viewModel.updateGeneratorOptions(newOpts)
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Advanced Readability Modes
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                GeneratorOptionToggle(
                    title = "Easy to Type (Avoid Ambiguous 1, l, 0, O)",
                    checked = options.easyToRead,
                    onCheckedChange = {
                        val newOpts = options.copy(easyToRead = it)
                        options = newOpts
                        viewModel.updateGeneratorOptions(newOpts)
                    }
                )
                GeneratorOptionToggle(
                    title = "Easy to Say (Pronounceable Syllables)",
                    checked = options.easyToSay,
                    onCheckedChange = {
                        val newOpts = options.copy(easyToSay = it)
                        options = newOpts
                        viewModel.updateGeneratorOptions(newOpts)
                    }
                )
            }
        }

        // If used as picker in Add/Edit screen
        if (onUsePassword != null) {
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = { onUsePassword(uiState.currentGeneratedPassword) },
                enabled = uiState.currentGeneratedPassword.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("use_generated_password_btn"),
                colors = ButtonDefaults.buttonColors(containerColor = Amber500),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(
                    Icons.Default.Key,
                    contentDescription = null,
                    tint = Color(0xFF0F172A)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Use this Password",
                    color = Color(0xFF0F172A),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun GeneratorOptionToggle(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Amber400,
                checkedTrackColor = Amber900(),
                uncheckedThumbColor = MaterialTheme.colorScheme.outline
            )
        )
    }
}

@Composable
fun Amber900(): Color = Amber500.copy(alpha = 0.5f)
