@file:Suppress("DEPRECATION")
package com.rohit.one.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rohit.one.data.CreditCard
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditCardScreen(
    modifier: Modifier = Modifier,
    card: CreditCard?,
    onSave: (String, String, String?) -> Unit,
    onDelete: (CreditCard) -> Unit,
    onNavigateUp: () -> Unit,
    onRequestFullNumber: (((String?) -> Unit) -> Unit)? = null
) {
    var name by remember { mutableStateOf(card?.cardholderName ?: "") }
    var number by remember { mutableStateOf("") }
    var brand by remember { mutableStateOf(card?.brand ?: "") }
    var revealed by remember { mutableStateOf(false) }

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
            FloatingActionButton(onClick = { onSave(name, number, brand) }) {
                Icon(Icons.Filled.Done, contentDescription = "Save card")
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).padding(16.dp)) {
            TextField(value = name, onValueChange = { name = it }, label = { Text("Cardholder name") }, modifier = Modifier.fillMaxWidth())
            TextField(value = brand, onValueChange = { brand = it }, label = { Text("Brand (optional)") }, modifier = Modifier.fillMaxWidth())
            TextField(value = if (revealed) number else if (card != null) "**** **** **** ${card.last4}" else number,
                onValueChange = { number = it }, label = { Text("Card number") }, modifier = Modifier.fillMaxWidth())

            TextButton(onClick = {
                if (card != null && onRequestFullNumber != null) {
                    if (!revealed) {
                        onRequestFullNumber.invoke { result ->
                            if (result != null) {
                                number = result
                                revealed = true
                            } else {
                                coroutineScope.launch { snackbarHostState.showSnackbar("Authentication failed") }
                            }
                        }
                    } else {
                        revealed = false
                        number = ""
                    }
                } else {
                    revealed = !revealed
                }
            }) {
                Text(if (revealed) "Hide number" else "Reveal number")
            }
        }
    }
}
