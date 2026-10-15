package com.janreins.vaultlock.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarOutline
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.janreins.vaultlock.data.VaultEntry
import com.janreins.vaultlock.generator.PasswordGenerator
import com.janreins.vaultlock.ui.VaultViewModel
import com.janreins.vaultlock.ui.EntryValidation
import com.janreins.vaultlock.ui.theme.Amber400
import com.janreins.vaultlock.ui.theme.Amber500
import com.janreins.vaultlock.ui.theme.RedError

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditEntryScreen(
    entryId: Long?,
    viewModel: VaultViewModel,
    draftKey: String,
    onNavigateBack: () -> Unit
) {
    val isEdit = entryId != null && entryId > 0
    val uiState by viewModel.uiState.collectAsState()
    val existingEntry = uiState.allEntries.find { it.id == entryId }
    val draft = remember(viewModel, draftKey) { viewModel.entryDraft(draftKey) }
    var loadedEntryId by draft::loadedEntryId
    var createdAt by draft::createdAt
    var title by draft::title
    var username by draft::username
    var password by draft::password
    var url by draft::url
    var notes by draft::notes
    var totpSecret by draft::totpSecret
    var category by draft::category
    var isFavorite by draft::isFavorite

    val discardAndNavigateBack = {
        viewModel.discardEntryDraft(draftKey)
        onNavigateBack()
    }
    BackHandler(onBack = discardAndNavigateBack)

    var isPasswordVisible by remember { mutableStateOf(false) }
    var showGeneratorSheet by remember { mutableStateOf(false) }
    var titleError by remember { mutableStateOf(false) }
    var totpError by remember { mutableStateOf(false) }
    var isTotpVisible by remember { mutableStateOf(false) }

    // Load existing entry if editing
    LaunchedEffect(entryId, existingEntry) {
        if (entryId != null && entryId > 0) {
            val existing = existingEntry
            if (existing != null && loadedEntryId != entryId) {
                title = existing.title
                username = existing.username
                password = existing.password
                url = existing.url
                notes = existing.notes
                totpSecret = existing.totpSecret
                category = existing.category
                isFavorite = existing.isFavorite
                createdAt = existing.createdAt
                loadedEntryId = entryId
            }
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (isEdit) "Edit Entry" else "New Password Entry",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = discardAndNavigateBack,
                        modifier = Modifier.testTag("entry_back_btn")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { isFavorite = !isFavorite },
                        modifier = Modifier.testTag("entry_fav_toggle_btn")
                    ) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarOutline,
                            contentDescription = "Favorite",
                            tint = if (isFavorite) Amber400 else MaterialTheme.colorScheme.onSurfaceVariant
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
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            // Category selector chips
            Text(
                text = "CATEGORY",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Amber400,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("Login", "Card", "Secure Note").forEach { cat ->
                    val isSelected = category.equals(cat, ignoreCase = true)
                    FilterChip(
                        selected = isSelected,
                        onClick = { category = cat },
                        label = { Text(cat, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                        leadingIcon = {
                            when (cat) {
                                "Login" -> Icon(Icons.Default.Lock, null, Modifier.size(16.dp))
                                "Card" -> Icon(Icons.Default.CreditCard, null, Modifier.size(16.dp))
                                else -> Icon(Icons.Default.Description, null, Modifier.size(16.dp))
                            }
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Amber500,
                            selectedLabelColor = Color(0xFF0F172A),
                            selectedLeadingIconColor = Color(0xFF0F172A)
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Primary input card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Title Field
                    OutlinedTextField(
                        value = title,
                        onValueChange = {
                            title = it
                            titleError = false
                        },
                        label = { Text("Title / Service Name (e.g. Proton, Bitwarden)") },
                        leadingIcon = {
                            Icon(Icons.Default.Title, null, tint = Amber400)
                        },
                        isError = titleError,
                        supportingText = if (titleError) {
                            { Text("Title is required", color = RedError) }
                        } else null,
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Amber400,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("entry_input_title")
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Username / Email Field
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Username / Email / User ID") },
                        leadingIcon = {
                            Icon(Icons.Default.Person, null, tint = Amber400)
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Amber400,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("entry_input_username")
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Password Field with Generator button and visibility toggle
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password / PIN") },
                        leadingIcon = {
                            Icon(Icons.Default.Lock, null, tint = Amber400)
                        },
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { showGeneratorSheet = true },
                                    modifier = Modifier.testTag("entry_open_generator_btn")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AutoAwesome,
                                        contentDescription = "Generate Strong Password",
                                        tint = Amber400
                                    )
                                }
                                IconButton(
                                    onClick = { isPasswordVisible = !isPasswordVisible },
                                    modifier = Modifier.testTag("entry_toggle_pw_visibility")
                                ) {
                                    Icon(
                                        imageVector = if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = "Toggle Visibility",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        },
                        visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Amber400,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("entry_input_password")
                    )

                    // Strength indicator if password has text
                    if (password.isNotEmpty()) {
                        val strength = PasswordGenerator.evaluateStrength(password)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Strength: ${strength.label}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(strength.colorHex),
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${password.length} chars",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // TOTP Secret Field (2FA Authenticator)
                    OutlinedTextField(
                        value = totpSecret,
                        onValueChange = {
                            totpSecret = it
                            totpError = false
                        },
                        label = { Text("TOTP Secret Key (Optional 2FA)") },
                        leadingIcon = {
                            Icon(Icons.Default.Key, null, tint = Amber400)
                        },
                        trailingIcon = {
                            IconButton(onClick = { isTotpVisible = !isTotpVisible }) {
                                Icon(
                                    if (isTotpVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (isTotpVisible) "Hide TOTP secret" else "Show TOTP secret"
                                )
                            }
                        },
                        visualTransformation = if (isTotpVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
                        isError = totpError,
                        supportingText = {
                            Text(if (totpError) "Enter a valid Base32 secret (A–Z, 2–7)." else "Raw secret only; SHA-1, 6 digits, 30 seconds.")
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Amber400,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("entry_input_totp")
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Website URL Field
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("Website Address (Optional)") },
                        leadingIcon = {
                            Icon(Icons.Default.Language, null, tint = Amber400)
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Amber400,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("entry_input_url")
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Notes / Extra Details Field
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("Encrypted Notes / Security Questions") },
                        leadingIcon = {
                            Icon(Icons.Default.Description, null, tint = Amber400)
                        },
                        minLines = 3,
                        maxLines = 6,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Amber400,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("entry_input_notes")
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Save Action Button
            Button(
                onClick = {
                    if (title.isBlank()) {
                        titleError = true
                    } else if (!EntryValidation.isValidTotpSecret(totpSecret)) {
                        totpError = true
                    } else {
                        val entry = VaultEntry(
                            id = entryId ?: 0L,
                            title = title.trim(),
                            username = username.trim(),
                            password = password,
                            url = url.trim(),
                            notes = notes.trim(),
                            totpSecret = EntryValidation.normalizeTotpSecret(totpSecret),
                            category = category,
                            isFavorite = isFavorite,
                            createdAt = createdAt,
                            updatedAt = System.currentTimeMillis()
                        )
                        viewModel.saveEntry(entry) {
                            viewModel.discardEntryDraft(draftKey)
                            onNavigateBack()
                        }
                    }
                },
                enabled = !uiState.isSaving && (!isEdit || loadedEntryId == entryId),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("entry_save_btn"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Amber500)
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = Color(0xFF0F172A)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (uiState.isSaving) "Saving…" else if (isEdit) "Save Changes" else "Save Encrypted Item",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF0F172A)
                )
            }

            Spacer(modifier = Modifier.height(30.dp))
        }

        // Generator Bottom Sheet Picker
        if (showGeneratorSheet) {
            ModalBottomSheet(
                onDismissRequest = { showGeneratorSheet = false },
                sheetState = sheetState,
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                PasswordGeneratorContent(
                    viewModel = viewModel,
                    onUsePassword = { generated ->
                        // Never replace the entry's password with an empty generator value.
                        if (generated.isNotEmpty()) password = generated
                        showGeneratorSheet = false
                    },
                    onClose = { showGeneratorSheet = false }
                )
            }
        }
    }
}
