package com.lasnoches.neurochoice

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.lasnoches.neurochoice.data.OAuthUtils
import com.lasnoches.neurochoice.data.TasteResult
import com.lasnoches.neurochoice.data.TrackInfo
import com.lasnoches.neurochoice.ui.theme.NeuroChoiceTheme

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NeuroChoiceTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot(viewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(vm: AppViewModel) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Выбор нейронки") },
                actions = {
                    if (vm.token != null && vm.screen !is Screen.Login) {
                        IconButton(onClick = { vm.logout() }) {
                            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Выйти")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            AnimatedContent(
                targetState = vm.screen,
                label = "screen-transition",
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(140)) },
            ) { screen ->
                when (screen) {
                    is Screen.Login -> LoginScreen(onToken = vm::onLoginSuccess)
                    is Screen.LoadingTaste ->
                        CenteredMessage(text = "Читаю лайки и любимые жанры...", showSpinner = true)
                    is Screen.Home -> HomeScreen(vm = vm, taste = screen.taste)
                    is Screen.Working -> CenteredMessage(text = screen.stage, showSpinner = true)
                    is Screen.Review -> ReviewScreen(vm = vm, screen = screen)
                    is Screen.Result -> ResultScreen(result = screen, onRestart = vm::backToHome)
                    is Screen.ErrorScreen -> CenteredMessage(
                        text = screen.message,
                        showSpinner = false,
                        actionLabel = "Повторить",
                        onAction = vm::retryFromError,
                    )
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LoginScreen(onToken: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Войди в свой аккаунт Яндекса, чтобы приложение могло прочитать твои лайки " +
                "и создать плейлист.",
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            val token = url?.let { OAuthUtils.extractAccessToken(it) }
                            if (token != null) {
                                view?.stopLoading()
                                onToken(token)
                            }
                        }
                    }
                    loadUrl(OAuthUtils.AUTH_URL)
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeScreen(vm: AppViewModel, taste: TasteResult) {
    var advancedExpanded by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Лайкнутых треков: ${taste.likedTrackCount}. " +
                        "Известных исполнителей (буду избегать): ${taste.knownArtistCount}.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (taste.genres.isEmpty()) {
            Text(
                "Не нашлось любимых жанров по лайкам. Полайкай что-нибудь в Яндекс.Музыке и обнови.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(onClick = { vm.loadTaste() }) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Обновить")
            }
            return@Column
        }

        Text("Жанры", style = MaterialTheme.typography.titleMedium)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            taste.genres.forEach { genre ->
                val selected = genre.id in vm.selectedGenreIds
                FilterChip(
                    selected = selected,
                    onClick = { vm.toggleGenre(genre.id) },
                    label = { Text("${genre.title} (${genre.count})") },
                )
            }
        }

        OutlinedTextField(
            value = vm.playlistName,
            onValueChange = { vm.playlistName = it },
            label = { Text("Название плейлиста") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        TextButton(onClick = { advancedExpanded = !advancedExpanded }) {
            Text(if (advancedExpanded) "Скрыть настройки" else "Дополнительные настройки")
            Icon(if (advancedExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
        }

        if (advancedExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Треков на жанр: ${vm.perGenre}", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = vm.perGenre.toFloat(),
                    onValueChange = { vm.perGenre = it.toInt() },
                    valueRange = 5f..30f,
                    steps = 24,
                )
                Text(
                    "Макс. треков одного нового исполнителя: ${vm.maxPerArtist}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = vm.maxPerArtist.toFloat(),
                    onValueChange = { vm.maxPerArtist = it.toInt() },
                    valueRange = 1f..5f,
                    steps = 3,
                )
            }
        }

        Button(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                vm.startCuration()
            },
            enabled = vm.selectedGenreIds.isNotEmpty() && vm.playlistName.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Подобрать треки")
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        Button(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                vm.startCheerfulPlaylist()
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Favorite, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Маргарите, чтоб не грустила")
        }
    }
}

@Composable
private fun TrackRow(
    track: TrackInfo,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        AsyncImage(
            model = track.coverUrl,
            contentDescription = null,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop,
            error = rememberFallbackCoverPainter(),
            placeholder = rememberFallbackCoverPainter(),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.artistsLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

@Composable
private fun rememberFallbackCoverPainter() = rememberVectorPainter(Icons.Filled.MusicNote)

@Composable
private fun ReviewScreen(vm: AppViewModel, screen: Screen.Review) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "«${screen.title}»  —  выбрано ${vm.selectedTrackIds.size} из ${screen.tracks.size}",
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.titleMedium,
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
        ) {
            items(screen.tracks, key = { it.id }) { track ->
                val checked = track.id in vm.selectedTrackIds
                TrackRow(
                    track = track,
                    modifier = Modifier.clickable { vm.toggleTrackSelection(track.id) },
                ) {
                    Checkbox(checked = checked, onCheckedChange = { vm.toggleTrackSelection(track.id) })
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = { vm.cancelReview() }, modifier = Modifier.weight(1f)) {
                Text("Отмена")
            }
            Button(
                onClick = { vm.confirmReview() },
                enabled = vm.selectedTrackIds.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Создать (${vm.selectedTrackIds.size})")
            }
        }
    }
}

@Composable
private fun ResultScreen(result: Screen.Result, onRestart: () -> Unit) {
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Готово! Треков в плейлисте: ${result.trackCount}", style = MaterialTheme.typography.titleMedium)

        if (result.playlistUrl.isNotBlank()) {
            Button(
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result.playlistUrl)))
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Открыть в Яндекс.Музыке")
            }
        }

        OutlinedButton(onClick = onRestart, modifier = Modifier.fillMaxWidth()) {
            Text("Подобрать ещё раз")
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(result.tracks, key = { it.id }) { track ->
                TrackRow(track = track)
            }
        }
    }
}

@Composable
private fun CenteredMessage(
    text: String,
    showSpinner: Boolean,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (showSpinner) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
        }
        Text(text, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}
