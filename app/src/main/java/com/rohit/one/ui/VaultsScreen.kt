@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@file:Suppress("DEPRECATION", "UNUSED_PARAMETER")
package com.rohit.one.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.ui.res.painterResource
import com.rohit.one.R
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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
    onCardClick: (CreditCard) -> Unit,
    signedInUsername: String? = null,
    onSignOut: (suspend () -> Unit)? = null
) {
    val passwords by vaultsViewModel.passwords.collectAsState(initial = emptyList())
    val cards by vaultsViewModel.cards.collectAsState(initial = emptyList())

    var selectedTab by remember { mutableStateOf(0) } // 0 = Passwords, 1 = Cards

    Scaffold(
        modifier = modifier,
        topBar = { /* no top bar for Vaults screen per request */ },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                if (selectedTab == 0) onAddPassword() else onAddCard()
            }) {
                Icon(Icons.Filled.Add, contentDescription = "Add")
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            Column(modifier = Modifier.padding(16.dp)) {
                PrimaryTabRow(selectedTabIndex = selectedTab) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                        Text("Passwords", modifier = Modifier.padding(12.dp))
                    }
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                        Text("Cards", modifier = Modifier.padding(12.dp))
                    }
                }

                // Swipeable content using pointerInput with accumulated drag and onDragEnd to decide page change
                VaultsContent(
                    passwords = passwords,
                    cards = cards,
                    selectedTab = selectedTab,
                    onPasswordClick = onPasswordClick,
                    onCardClick = onCardClick,
                    onTabChanged = { newTab -> selectedTab = newTab },
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
fun VaultsContent(
    passwords: List<Password>,
    cards: List<CreditCard>,
    selectedTab: Int,
    onPasswordClick: (Password) -> Unit,
    onCardClick: (CreditCard) -> Unit,
    onTabChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Accumulate horizontal drag in pixels over the gesture and decide onDragEnd
                var totalX = 0f
                detectHorizontalDragGestures(
                    onHorizontalDrag = { _, dragAmount ->
                        totalX += dragAmount
                        // If user is clearly dragging horizontally, we do not consume to avoid blocking vertical scroll;
                        // instead, rely on threshold on drag end to switch pages.
                    },
                    onDragEnd = {
                        val threshold = 150f
                        if (totalX > threshold) {
                            // swiped right
                            onTabChanged((selectedTab - 1).coerceAtLeast(0))
                        } else if (totalX < -threshold) {
                            // swiped left
                            onTabChanged((selectedTab + 1).coerceAtMost(1))
                        }
                        totalX = 0f
                    },
                    onDragCancel = {
                        totalX = 0f
                    }
                )
            }
    ) {
        if (selectedTab == 0) {
            LazyColumn(
                modifier = Modifier.padding(top = 0.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(passwords) { password ->
                    PasswordListItem(password, onClick = { onPasswordClick(password) })
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                contentPadding = PaddingValues(top = 0.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(cards) { card ->
                    CardListItem(card, onClick = { onCardClick(card) })
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
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val brandLower = card.brand?.lowercase()?.trim() ?: ""
            val logoPainter = when {
                "visa" in brandLower -> painterResource(id = R.drawable.ic_card_visa)
                "master" in brandLower || "mc" == brandLower -> painterResource(id = R.drawable.ic_card_mastercard)
                "amex" in brandLower || "american express" in brandLower -> painterResource(id = R.drawable.ic_card_amex)
                "discover" in brandLower -> painterResource(id = R.drawable.ic_card_discover)
                "jcb" in brandLower -> painterResource(id = R.drawable.ic_card_jcb)
                "diners" in brandLower -> painterResource(id = R.drawable.ic_card_diners)
                "rupay" in brandLower -> painterResource(id = R.drawable.ic_card_rupay)
                else -> null
            }

            if (logoPainter != null) {
                Icon(
                    painter = logoPainter,
                    contentDescription = "Card brand icon",
                    tint = Color.Unspecified
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.CreditCard,
                    contentDescription = "Card icon",
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(text = card.cardholderName, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "**** **** **** ${card.last4}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
