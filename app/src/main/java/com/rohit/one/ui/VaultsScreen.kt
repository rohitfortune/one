@file:Suppress("DEPRECATION", "UNUSED_PARAMETER")
package com.rohit.one.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rohit.one.data.Password
import com.rohit.one.data.CreditCard
import com.rohit.one.viewmodel.VaultsViewModel

@Composable
fun VaultsScreen(
    modifier: Modifier = Modifier,
    vaultsViewModel: VaultsViewModel,
    onAddPassword: () -> Unit,
    onAddCard: () -> Unit,
    onPasswordClick: (Password) -> Unit,
    onCardClick: (CreditCard) -> Unit
) {
    val passwords by vaultsViewModel.passwords.collectAsState(initial = emptyList())
    val cards by vaultsViewModel.cards.collectAsState(initial = emptyList())

    var selectedTab by remember { mutableStateOf(0) } // 0 = Passwords, 1 = Cards

    Scaffold(
        modifier = modifier,
        floatingActionButton = {
            FloatingActionButton(onClick = {
                if (selectedTab == 0) onAddPassword() else onAddCard()
            }) {
                Icon(Icons.Filled.Add, contentDescription = "Add")
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).padding(16.dp)) {
            // Header
            Text(text = "Vault", style = MaterialTheme.typography.titleLarge)

            // Tabs under header
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                    Text("Passwords", modifier = Modifier.padding(12.dp))
                }
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                    Text("Cards", modifier = Modifier.padding(12.dp))
                }
            }

            // Content: show list corresponding to selected tab
            if (selectedTab == 0) {
                LazyColumn(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(passwords) { password ->
                        PasswordListItem(password, onClick = { onPasswordClick(password) })
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(cards) { card ->
                        CardListItem(card, onClick = { onCardClick(card) })
                    }
                }
            }
        }
    }
}

@Composable
fun PasswordListItem(password: Password, onClick: () -> Unit) {
    Card(modifier = Modifier.clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = password.title, style = MaterialTheme.typography.titleMedium)
            Text(text = password.username, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun CardListItem(card: CreditCard, onClick: () -> Unit) {
    Card(modifier = Modifier.clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = card.cardholderName, style = MaterialTheme.typography.titleMedium)
            Text(text = "**** **** **** ${card.last4}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
