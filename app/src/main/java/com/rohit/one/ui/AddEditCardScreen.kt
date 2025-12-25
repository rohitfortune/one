@file:Suppress("DEPRECATION")
package com.rohit.one.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.rohit.one.data.CreditCard
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditCardScreen(
    modifier: Modifier = Modifier,
    card: CreditCard?,
    onSave: (String, String, String?, String?, String?) -> Unit,
    onDelete: (CreditCard) -> Unit,
    onNavigateUp: () -> Unit,
    onRequestFullNumber: (((String?) -> Unit) -> Unit)? = null
) {
    val isNewCard = card == null

    var name by remember { mutableStateOf(card?.cardholderName ?: "") }
    var number by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf(card?.brand ?: "") }
    var expiryDigits by remember { mutableStateOf(card?.expiry?.filter { it.isDigit() } ?: "") }
    var expiryTextField by remember {
        mutableStateOf(TextFieldValue(text = formatExpiry(expiryDigits), selection = TextRange(formatExpiry(expiryDigits).length)))
    }
    var securityCode by remember { mutableStateOf(card?.securityCode ?: "") }
    var revealed by remember { mutableStateOf(isNewCard) }

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(text = if (card == null) "Add Card" else "Edit Card") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (card != null) {
                        IconButton(onClick = { onDelete(card) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                val trimmedName = name.trim()
                val trimmedNumber = number.trim()
                val trimmedNickname = nickname.trim()
                val trimmedExpiry = expiryTextField.text.trim()
                val trimmedCvc = securityCode.trim()

                // If all primary fields are empty, just close the screen without saving
                if (trimmedName.isEmpty() &&
                    trimmedNumber.isEmpty() &&
                    trimmedNickname.isEmpty() &&
                    trimmedExpiry.isEmpty() &&
                    trimmedCvc.isEmpty()
                ) {
                    onNavigateUp()
                    return@FloatingActionButton
                }

                // Detect brand from card number (BIN) if not explicitly provided
                val inferredBrand = detectCardBrand(trimmedNumber)
                val finalBrand = when {
                    trimmedNickname.isNotBlank() -> trimmedNickname
                    inferredBrand != null -> inferredBrand
                    else -> null
                }

                onSave(
                    trimmedName,
                    trimmedNumber,
                    finalBrand,
                    trimmedExpiry.ifBlank { null },
                    trimmedCvc.ifBlank { null }
                )
            }) {
                Icon(Icons.Filled.Done, contentDescription = "Save card")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Cardholder name") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = nickname,
                onValueChange = { nickname = it },
                label = { Text("Card nickname (optional)") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = if (revealed) number else "",
                onValueChange = { input ->
                    if (!revealed) return@OutlinedTextField
                    // Filter to digits only and limit to 16 digits
                    val digits = input.filter { it.isDigit() }.take(16)
                    number = digits
                },
                label = {
                    val mask = if (card != null && card.last4.isNotEmpty()) "(**** **** **** ${card.last4})" else ""
                    Text(text = listOf("Card number", mask).filter { it.isNotBlank() }.joinToString(" "))
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = if (revealed) expiryTextField else TextFieldValue(if (expiryDigits.isNotBlank()) "**/**" else ""),
                    onValueChange = { tfv: TextFieldValue ->
                        if (!revealed) return@OutlinedTextField

                        val isAtEnd = tfv.selection.end == tfv.text.length
                        val onlyDigits = tfv.text.filter { it.isDigit() }
                        val newDigits = onlyDigits.take(4)

                        val newText = if (isAtEnd) {
                            formatExpiry(newDigits)
                        } else {
                            tfv.text
                        }

                        expiryDigits = newDigits
                        val newCursor = newText.length.coerceIn(0, newText.length)
                        expiryTextField = TextFieldValue(text = newText, selection = TextRange(newCursor))
                    },
                    label = { Text("Expiry date (MM/YY)") },
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    shape = RoundedCornerShape(12.dp)
                )
                OutlinedTextField(
                    value = if (revealed) securityCode else if (securityCode.isNotBlank()) "****" else "",
                    onValueChange = { raw ->
                        if (!revealed) return@OutlinedTextField
                        // Allow only digits and limit to 4 characters
                        securityCode = raw.filter { it.isDigit() }.take(4)
                    },
                    label = { Text("Security code") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                )
            }

            if (!isNewCard) {
                TextButton(onClick = {
                    if (card != null && onRequestFullNumber != null) {
                        if (!revealed) {
                            onRequestFullNumber.invoke { result ->
                                if (result != null) {
                                    number = result.filter { it.isDigit() }.take(16)
                                    revealed = true
                                } else {
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar("Authentication failed")
                                    }
                                }
                            }
                        } else {
                            revealed = false
                        }
                    } else {
                        revealed = !revealed
                    }
                }) {
                    Text(if (revealed) "Hide details" else "Reveal details")
                }
            }
        }
    }
}

private fun formatExpiry(digits: String): String = when (digits.length) {
    0 -> ""
    1 -> digits
    2 -> digits                // "MM"
    3 -> digits.substring(0, 2) + "/" + digits.substring(2, 3)    // "MM/Y"
    else -> digits.substring(0, 2) + "/" + digits.substring(2, 4) // "MM/YY"
}

// Simple brand detection from card number prefix
private fun detectCardBrand(number: String): String? {
    val digits = number.filter { it.isDigit() }
    if (digits.isEmpty()) return null

    return when {
        // Visa: starts with 4
        digits.startsWith("4") -> "Visa"

        // Mastercard: 51–55 or 2221–2720
        digits.length >= 2 && digits.take(2).toIntOrNull() in 51..55 -> "Mastercard"
        digits.length >= 4 && digits.take(4).toIntOrNull() in 2221..2720 -> "Mastercard"

        // American Express: 34 or 37
        digits.length >= 2 && (digits.startsWith("34") || digits.startsWith("37")) -> "Amex"

        // Discover: 6011, 65, 644–649
        digits.startsWith("6011") -> "Discover"
        digits.startsWith("65") -> "Discover"
        digits.length >= 3 && digits.take(3).toIntOrNull() in 644..649 -> "Discover"

        // JCB: 3528–3589
        digits.length >= 4 && digits.take(4).toIntOrNull() in 3528..3589 -> "JCB"

        // RuPay (India): common ranges (not exhaustive, but covers typical BINs)
        // 60, 65, 81, 82 series etc. Here we check a few widely documented prefixes.
        digits.startsWith("60") || digits.startsWith("65") ||
                digits.startsWith("81") || digits.startsWith("82") -> "RuPay"

        // Diners Club (optional extra): 300–305, 36, 38–39
        digits.length >= 3 && digits.take(3).toIntOrNull() in 300..305 -> "Diners Club"
        digits.startsWith("36") || digits.startsWith("38") || digits.startsWith("39") -> "Diners Club"

        else -> null
    }
}
