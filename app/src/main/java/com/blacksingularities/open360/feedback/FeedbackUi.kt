package com.blacksingularities.open360.feedback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.Context
import java.util.Locale

/** Wysyłka w tle (korutyny) – nakładki na blokujące funkcje z [Feedback]. */
suspend fun Feedback.send(context: Context, kind: Feedback.Kind, message: String, email: String, includeDiagnostics: Boolean): Result<Unit> =
    withContext(Dispatchers.IO) { sendBlocking(context, kind, message, email, includeDiagnostics) }

suspend fun Feedback.sendCrash(context: Context, crash: Feedback.PendingCrash, comment: String, email: String): Result<Unit> =
    withContext(Dispatchers.IO) { sendCrashBlocking(context, crash, comment, email) }

suspend fun Feedback.sendCrashText(context: Context, stack: String, comment: String, email: String): Result<Unit> =
    withContext(Dispatchers.IO) { sendCrashTextBlocking(context, stack, comment, email) }

/** Teksty modułu – polski albo angielski, zależnie od języka telefonu. */
object FeedbackText {
    private val pl get() = Locale.getDefault().language == "pl"
    val entryTitle get() = if (pl) "Zgłoś błąd lub sugestię" else "Report a bug or suggestion"
    val entrySubtitle get() = if (pl) "Wiadomość trafia prosto do autora" else "Goes straight to the developer"
    val reportTitle get() = if (pl) "Zgłoś błąd lub sugestię" else "Report a bug or suggest a feature"
    val bug get() = if (pl) "Błąd" else "Bug"
    val feature get() = if (pl) "Sugestia" else "Suggestion"
    val bugHint get() = if (pl) "Co się stało? Co robiłeś tuż przed błędem?" else "What happened? What were you doing right before?"
    val featureHint get() = if (pl) "Czego brakuje albo co można zrobić lepiej?" else "What is missing or could work better?"
    val email get() = if (pl) "E-mail (opcjonalnie)" else "Email (optional)"
    val emailHint get() = if (pl) "Tylko jeśli chcesz dostać odpowiedź" else "Only if you'd like a reply"
    val diagnostics get() = if (pl) "Dołącz wersję aplikacji i model urządzenia" else "Include app version and device model"
    val send get() = if (pl) "Wyślij" else "Send"
    val cancel get() = if (pl) "Anuluj" else "Cancel"
    val close get() = if (pl) "Zamknij" else "Close"
    val thanks get() = if (pl) "Dziękuję! Zgłoszenie dotarło." else "Thank you! Your report was sent."
    val failed get() = if (pl) "Nie udało się wysłać. Sprawdź połączenie i spróbuj ponownie." else "Couldn't send. Check your connection and try again."
    val tooShort get() = if (pl) "Opisz problem w kilku słowach." else "Please describe it in a few words."
    val crashTitle get() = if (pl) "Aplikacja zamknęła się z błędem" else "The app closed unexpectedly"
    val crashBody get() = if (pl) "Ostatnio aplikacja zakończyła działanie przez błąd. Wysłać autorowi raport? Zawiera opis błędu, wersję aplikacji i model urządzenia – bez Twoich danych." else "The app recently stopped because of an error. Send a report to the developer? It contains the error details, app version and device model – none of your data."
    val crashComment get() = if (pl) "Co robiłeś przed awarią? (opcjonalnie)" else "What were you doing? (optional)"
    val showDetails get() = if (pl) "Pokaż szczegóły" else "Show details"
    val hideDetails get() = if (pl) "Ukryj szczegóły" else "Hide details"
    val sendReport get() = if (pl) "Wyślij raport" else "Send report"
    val dontSend get() = if (pl) "Nie wysyłaj" else "Don't send"
    val copy get() = if (pl) "Kopiuj" else "Copy"
}

/**
 * Wpis w ustawieniach otwierający [FeedbackDialog]. [entry] rysuje wiersz w stylu aplikacji
 * i woła `open()` po dotknięciu; podpis i opis są w [FeedbackText.entryTitle] / [FeedbackText.entrySubtitle].
 */
@Composable
fun FeedbackEntry(entry: @Composable (open: () -> Unit) -> Unit) {
    var show by rememberSaveable { mutableStateOf(false) }
    entry { show = true }
    if (show) FeedbackDialog(onDismiss = { show = false })
}

/**
 * Formularz zgłoszenia: błąd albo sugestia funkcjonalności, z opcjonalnym e-mailem.
 * Pokaż go z ustawień / ekranu „O aplikacji”.
 */
@Composable
fun FeedbackDialog(onDismiss: () -> Unit, initialKind: Feedback.Kind = Feedback.Kind.BUG) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var kind by rememberSaveable { mutableStateOf(initialKind) }
    var message by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var diagnostics by rememberSaveable { mutableStateOf(true) }
    var sending by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text(FeedbackText.reportTitle) },
        text = {
            if (sent) {
                Text(FeedbackText.thanks)
            } else {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = kind == Feedback.Kind.BUG,
                            onClick = { kind = Feedback.Kind.BUG },
                            label = { Text(FeedbackText.bug) },
                        )
                        FilterChip(
                            selected = kind == Feedback.Kind.FEATURE,
                            onClick = { kind = Feedback.Kind.FEATURE },
                            label = { Text(FeedbackText.feature) },
                        )
                    }
                    OutlinedTextField(
                        value = message,
                        onValueChange = { message = it.take(8000); error = null },
                        placeholder = { Text(if (kind == Feedback.Kind.BUG) FeedbackText.bugHint else FeedbackText.featureHint) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                        enabled = !sending,
                    )
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it.take(200) },
                        label = { Text(FeedbackText.email) },
                        supportingText = { Text(FeedbackText.emailHint) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !sending,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = diagnostics, onCheckedChange = { diagnostics = it }, enabled = !sending)
                        Text(FeedbackText.diagnostics, style = MaterialTheme.typography.bodySmall)
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = {
            if (sent) {
                TextButton(onClick = onDismiss) { Text(FeedbackText.close) }
            } else {
                TextButton(
                    enabled = !sending,
                    onClick = {
                        if (message.trim().length < 3) {
                            error = FeedbackText.tooShort
                            return@TextButton
                        }
                        sending = true
                        scope.launch {
                            Feedback.send(context, kind, message, email, diagnostics)
                                .onSuccess { sent = true }
                                .onFailure { error = FeedbackText.failed }
                            sending = false
                        }
                    },
                ) {
                    if (sending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(FeedbackText.send)
                }
            }
        },
        dismissButton = {
            if (!sent) TextButton(onClick = onDismiss, enabled = !sending) { Text(FeedbackText.cancel) }
        },
    )
}

/**
 * Pytanie o wysłanie raportu po awarii. Umieść raz w korzeniu interfejsu
 * (np. tuż w `setContent { … }`); pokazuje się tylko, gdy poprzednie
 * uruchomienie skończyło się błędem.
 */
@Composable
fun CrashReportPrompt() {
    val context = LocalContext.current
    var crash by remember { mutableStateOf<Feedback.PendingCrash?>(null) }
    LaunchedEffect(Unit) { crash = Feedback.pendingCrash(context) }
    val current = crash ?: return
    CrashReportDialog(
        stack = current.stack,
        onDismiss = {
            Feedback.discardPendingCrash(context)
            crash = null
        },
        send = { comment, email -> Feedback.sendCrash(context, current, comment, email) },
    )
}

/**
 * Okno raportu o błędzie dla podanego opisu awarii: komentarz i e-mail są opcjonalne,
 * szczegóły można podejrzeć i skopiować. [send] domyślnie wysyła tekst jako raport.
 */
@Composable
fun CrashReportDialog(
    stack: String,
    onDismiss: () -> Unit,
    send: (suspend (comment: String, email: String) -> Result<Unit>)? = null,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var comment by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var details by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = {},
        title = { Text(FeedbackText.crashTitle) },
        text = {
            if (sent) {
                Text(FeedbackText.thanks)
            } else {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(FeedbackText.crashBody, style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = comment,
                        onValueChange = { comment = it.take(4000) },
                        placeholder = { Text(FeedbackText.crashComment) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !sending,
                    )
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it.take(200) },
                        label = { Text(FeedbackText.email) },
                        supportingText = { Text(FeedbackText.emailHint) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !sending,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { details = !details }) {
                            Text(if (details) FeedbackText.hideDetails else FeedbackText.showDetails)
                        }
                        TextButton(onClick = { clipboard.setText(AnnotatedString(stack)) }) {
                            Text(FeedbackText.copy)
                        }
                    }
                    if (details) {
                        Text(
                            stack.lines().take(40).joinToString("\n"),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            lineHeight = 13.sp,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = {
            if (sent) {
                TextButton(onClick = onDismiss) { Text(FeedbackText.close) }
            } else {
                TextButton(
                    enabled = !sending,
                    onClick = {
                        sending = true
                        scope.launch {
                            val result = send?.invoke(comment, email)
                                ?: Feedback.sendCrashText(context, stack, comment, email)
                            result
                                .onSuccess { sent = true }
                                .onFailure { error = FeedbackText.failed }
                            sending = false
                        }
                    },
                ) {
                    if (sending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(FeedbackText.sendReport)
                }
            }
        },
        dismissButton = {
            if (!sent) TextButton(enabled = !sending, onClick = onDismiss) { Text(FeedbackText.dontSend) }
        },
    )
}
