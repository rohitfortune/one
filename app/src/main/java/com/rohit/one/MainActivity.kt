package com.rohit.one

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Note
import androidx.compose.material.icons.filled.Password
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.compose.ui.platform.LocalContext
import androidx.biometric.BiometricPrompt
import com.rohit.one.data.Note
import com.rohit.one.data.Password
import com.rohit.one.data.CreditCard
import com.rohit.one.data.NoteDatabase
import com.rohit.one.data.NoteRepository
import com.rohit.one.data.PasswordRepository
import com.rohit.one.data.CreditCardRepository
import com.rohit.one.data.BackupRepository
import com.rohit.one.ui.BackupScreen
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.android.gms.drive.Drive
import com.google.android.gms.drive.DriveScopes
import com.rohit.one.ui.AddEditNoteScreen
import com.rohit.one.ui.AddEditPasswordScreen
import com.rohit.one.ui.AddEditCardScreen
import com.rohit.one.ui.VaultsScreen
import com.rohit.one.ui.FilesScreen
import com.rohit.one.ui.theme.OneTheme
import com.rohit.one.viewmodel.NotesViewModel
import com.rohit.one.viewmodel.VaultsViewModel
import androidx.navigation.NavBackStackEntry // Added import for NavBackStackEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

@Suppress("DEPRECATION")
enum class AppDestinations(
    val label: String,
    val icon: ImageVector,
) {
    NOTES("Notes", Icons.Filled.Note),
    PASSWORDS("Vault", Icons.Filled.Password),
    FILES("Files", Icons.Filled.Folder),
    BACKUP("Backup", Icons.Filled.Folder),
}

class MainActivity : FragmentActivity() {

    private lateinit var notesViewModel: NotesViewModel
    private lateinit var vaultsViewModel: VaultsViewModel
    private lateinit var googleSignInClient: GoogleSignInClient
    private val mainScope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val database = NoteDatabase.getDatabase(applicationContext)
        val repository = NoteRepository(database.noteDao())
        notesViewModel = ViewModelProvider(this, NotesViewModel.provideFactory(repository))[NotesViewModel::class.java]

        val passwordRepository = PasswordRepository(database.passwordDao(), applicationContext)
        val cardRepository = CreditCardRepository(database.creditCardDao(), applicationContext)
        vaultsViewModel = ViewModelProvider(this, VaultsViewModel.provideFactory(passwordRepository, cardRepository))[VaultsViewModel::class.java]

        // Google Sign-In client for Drive appData scope
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_APPDATA))
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        setContent {
            OneTheme {
                OneApp(notesViewModel = notesViewModel, vaultsViewModel = vaultsViewModel, googleSignInClient = googleSignInClient)
            }
        }
    }
}

suspend fun fetchAccessToken(context: Context, accountName: String?): String? = withContext(Dispatchers.IO) {
    if (accountName == null) return@withContext null
    try {
        val scope = "oauth2:${DriveScopes.DRIVE_APPDATA}"
        return@withContext GoogleAuthUtil.getToken(context, accountName, scope)
    } catch (e: UserRecoverableAuthException) {
        // Caller must handle starting the intent from the exception to get consent
        return@withContext null
    } catch (e: Exception) {
        return@withContext null
    }
}

@Composable
fun OneApp(
    notesViewModel: NotesViewModel = viewModel(),
    vaultsViewModel: VaultsViewModel = viewModel(),
    googleSignInClient: GoogleSignInClient
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val activity = context as Activity

    // Backup repository instance
    val backupRepository = remember { BackupRepository(context.applicationContext) }

    // Track signed in account
    var signedInAccount by rememberSaveable { mutableStateOf<String?>(GoogleSignIn.getLastSignedInAccount(context)?.account?.name) }

    // Launcher for sign-in
    val signInLauncher = rememberLauncherForActivityResult(contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(Exception::class.java)
            signedInAccount = account?.account?.name
        } catch (_: Exception) {
            signedInAccount = null
        }
    }

    // access token provider to be passed to BackupScreen
    val accessTokenProvider: suspend () -> String? = {
        fetchAccessToken(context, signedInAccount)
    }

    NavHost(navController = navController, startDestination = "main") {
        composable(route = "main") {
            MainScreen(
                onNavigateToAddNote = { navController.navigate("addEditNote/-1") },
                onNavigateToEditNote = { noteId -> navController.navigate("addEditNote/$noteId") },
                onNavigateToAddPassword = { navController.navigate("addEditPassword/-1") },
                onNavigateToEditPassword = { pwId -> navController.navigate("addEditPassword/$pwId") },
                onNavigateToAddCard = { navController.navigate("addEditCard/-1") },
                onNavigateToEditCard = { cardId -> navController.navigate("addEditCard/$cardId") },
                notesViewModel = notesViewModel,
                vaultsViewModel = vaultsViewModel,
                activity = activity,
                backupRepository = backupRepository,
                accessTokenProvider = accessTokenProvider,
                onSignIn = {
                    val signInIntent: Intent = googleSignInClient.signInIntent
                    signInLauncher.launch(signInIntent)
                }
            )
        }
        composable(
            route = "addEditNote/{noteId}",
            arguments = listOf(navArgument("noteId") { type = NavType.IntType })
        ) { backStackEntry ->
            AddEditNoteRoute(
                navController = navController,
                notesViewModel = notesViewModel,
                backStackEntry = backStackEntry
            )
        }
        composable(
            route = "addEditPassword/{pwId}",
            arguments = listOf(navArgument("pwId") { type = NavType.IntType })
        ) { backStackEntry ->
            AddEditPasswordRoute(
                navController = navController,
                vaultsViewModel = vaultsViewModel,
                backStackEntry = backStackEntry
            )
        }
        composable(
            route = "addEditCard/{cardId}",
            arguments = listOf(navArgument("cardId") { type = NavType.IntType })
        ) { backStackEntry ->
            AddEditCardRoute(
                navController = navController,
                vaultsViewModel = vaultsViewModel,
                backStackEntry = backStackEntry
            )
        }
    }
}

@Composable
fun AddEditPasswordRoute(
    navController: NavController,
    vaultsViewModel: VaultsViewModel,
    backStackEntry: NavBackStackEntry
) {
    val pwId = backStackEntry.arguments?.getInt("pwId")
    val pwToEdit = vaultsViewModel.passwords.collectAsState().value.find { it.id == pwId }
    val context = LocalContext.current

    // requestRawPassword: caller will trigger biometric auth and then receive the secret via callback
    val requestRawPassword: (((String?) -> Unit) -> Unit)? = pwToEdit?.let { pw ->
        { onResult ->
            // pw is non-null here (let scope). Proceed to prompt for biometric auth and return secret via onResult.
            val executor = ContextCompat.getMainExecutor(context)
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Authenticate")
                .setSubtitle("Authenticate to reveal password")
                .setNegativeButtonText("Cancel")
                .build()
            val biometricPrompt = BiometricPrompt(context as FragmentActivity, executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(result)
                        onResult(vaultsViewModel.getRawPassword(pw.uuid))
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
                        onResult(null)
                    }

                    override fun onAuthenticationFailed() {
                        super.onAuthenticationFailed()
                        onResult(null)
                    }
                })
            biometricPrompt.authenticate(promptInfo)
        }
    }

    AddEditPasswordScreen(
        password = pwToEdit,
        onSave = { title, username, rawPassword ->
            if (pwToEdit == null) {
                vaultsViewModel.addPassword(title, username, rawPassword)
            } else {
                vaultsViewModel.updatePassword(pwToEdit.copy(title = title, username = username), if (rawPassword.isBlank()) null else rawPassword)
            }
            navController.popBackStack()
        },
        onDelete = { pw ->
            vaultsViewModel.deletePassword(pw)
            navController.popBackStack()
        },
        onNavigateUp = { navController.popBackStack() },
        requestRawPassword = requestRawPassword
    )
}

@Composable
fun AddEditCardRoute(
    navController: NavController,
    vaultsViewModel: VaultsViewModel,
    backStackEntry: NavBackStackEntry
) {
    val cardId = backStackEntry.arguments?.getInt("cardId")
    val cardToEdit = vaultsViewModel.cards.collectAsState().value.find { it.id == cardId }
    val context = LocalContext.current

    val requestFullNumber: (((String?) -> Unit) -> Unit)? = cardToEdit?.let { card ->
        { onResult ->
            val executor = ContextCompat.getMainExecutor(context)
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Authenticate")
                .setSubtitle("Authenticate to reveal card number")
                .setNegativeButtonText("Cancel")
                .build()
            val biometricPrompt = BiometricPrompt(context as FragmentActivity, executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(result)
                        onResult(vaultsViewModel.getFullNumber(card.uuid))
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
                        onResult(null)
                    }

                    override fun onAuthenticationFailed() {
                        super.onAuthenticationFailed()
                        onResult(null)
                    }
                })
            biometricPrompt.authenticate(promptInfo)
        }
    }

    AddEditCardScreen(
        card = cardToEdit,
        onSave = { name, fullNumber, brand ->
            if (cardToEdit == null) {
                vaultsViewModel.addCard(name, fullNumber, brand)
            } else {
                vaultsViewModel.updateCard(cardToEdit.copy(cardholderName = name, brand = brand), if (fullNumber.isBlank()) null else fullNumber)
            }
            navController.popBackStack()
        },
        onDelete = { c ->
            vaultsViewModel.deleteCard(c)
            navController.popBackStack()
        },
        onNavigateUp = { navController.popBackStack() },
        onRequestFullNumber = requestFullNumber
    )
}

@Composable
fun MainScreen(
    onNavigateToAddNote: () -> Unit,
    onNavigateToEditNote: (Int) -> Unit,
    onNavigateToAddPassword: () -> Unit,
    onNavigateToEditPassword: (Int) -> Unit,
    onNavigateToAddCard: () -> Unit,
    onNavigateToEditCard: (Int) -> Unit,
    notesViewModel: NotesViewModel,
    vaultsViewModel: VaultsViewModel,
    activity: Activity,
    backupRepository: BackupRepository,
    accessTokenProvider: suspend () -> String?,
    onSignIn: () -> Unit
) {
    // Specify type parameter explicitly to help inference
    var currentDestination by rememberSaveable { mutableStateOf<AppDestinations>(AppDestinations.NOTES) }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            AppDestinations.entries.forEach {
                item(
                    icon = {
                        Icon(
                            it.icon,
                            contentDescription = it.label
                        )
                    },
                    label = { Text(it.label) },
                    selected = it == currentDestination,
                    onClick = { currentDestination = it }
                )
            }
        }
    ) {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            when (currentDestination) {
                AppDestinations.NOTES -> NotesScreen(
                    modifier = Modifier.padding(innerPadding),
                    notesViewModel = notesViewModel,
                    onAddNoteClicked = onNavigateToAddNote,
                    onNoteClicked = onNavigateToEditNote
                )
                AppDestinations.PASSWORDS -> VaultsScreen(
                    modifier = Modifier.padding(innerPadding),
                    vaultsViewModel = vaultsViewModel,
                    onAddPassword = onNavigateToAddPassword,
                    onAddCard = onNavigateToAddCard,
                    onPasswordClick = { pw -> onNavigateToEditPassword(pw.id) },
                    onCardClick = { card -> onNavigateToEditCard(card.id) }
                )
                AppDestinations.FILES -> FilesScreen(modifier = Modifier.padding(innerPadding))
                AppDestinations.BACKUP -> BackupScreen(
                    modifier = Modifier.padding(innerPadding),
                    activity = activity,
                    backupRepository = backupRepository,
                    accessTokenProvider = accessTokenProvider,
                    onSignIn = onSignIn
                )
            }
        }
    }
}

@Composable
fun AddEditNoteRoute(
    navController: NavController,
    notesViewModel: NotesViewModel,
    backStackEntry: NavBackStackEntry
) {
    val noteId = backStackEntry.arguments?.getInt("noteId")
    val noteToEdit = notesViewModel.notes.collectAsState().value.find { it.id == noteId }

    AddEditNoteScreen(
        note = noteToEdit,
        onSave = { note ->
            if (noteToEdit == null) {
                notesViewModel.addNote(note)
            } else {
                notesViewModel.updateNote(note.copy(id = noteToEdit.id))
            }
            navController.popBackStack()
        },
        onDelete = { note ->
            notesViewModel.deleteNote(note)
            navController.popBackStack()
        },
        onNavigateUp = { navController.popBackStack() }
    )
}


@Composable
fun NotesScreen(
    modifier: Modifier = Modifier,
    notesViewModel: NotesViewModel,
    onAddNoteClicked: () -> Unit,
    onNoteClicked: (Int) -> Unit
) {
    val notes by notesViewModel.notes.collectAsState()

    Scaffold(
        modifier = modifier,
        floatingActionButton = {
            FloatingActionButton(onClick = onAddNoteClicked) {
                Icon(Icons.Filled.Add, contentDescription = "Add note")
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(notes) { note ->
                NoteItem(note, onClick = { onNoteClicked(note.id) })
            }
        }
    }
}

@Composable
fun NoteItem(note: Note, onClick: () -> Unit) {
    Card(modifier = Modifier.clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = note.title, style = MaterialTheme.typography.titleMedium)
            Text(text = note.content, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
