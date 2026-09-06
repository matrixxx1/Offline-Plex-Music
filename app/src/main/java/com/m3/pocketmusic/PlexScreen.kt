package com.m3.pocketmusic

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable fun PlexScreen(vm: MusicViewModel, state: LibraryState, busy: Boolean, openLibrary: () -> Unit, chooseFolder: () -> Unit) {
    val context = LocalContext.current
    val connection by vm.connection.collectAsStateWithLifecycle()
    val servers by vm.servers.collectAsStateWithLifecycle()
    val signingIn by vm.signingIn.collectAsStateWithLifecycle()
    val loginUrl by vm.loginUrl.collectAsStateWithLifecycle()
    var advanced by remember { mutableStateOf(false) }
    var url by remember(connection.url) { mutableStateOf(connection.url) }
    var token by remember(connection.token) { mutableStateOf(connection.token) }
    fun browser(address: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(address)))
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Your Plex music, connected", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Sign in, choose your server, and import your music library. Tap any imported track to stream immediately. Downloads are optional.", fontSize = 14.sp)
        if (state.offline) {
            Text("Offline only is on. Enable online access to connect or import.")
            Button(onClick = { vm.settings(offline = false) }) { Text("Enable online access") }
        }
        if (connection.serverId.isNotBlank()) {
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Saved Plex connection", fontWeight = FontWeight.Bold)
                Text(connection.url, fontSize = 12.sp)
                Text("${state.tracks.count { it.remoteKey.isNotBlank() }} Plex tracks in your library", fontSize = 13.sp)
                Button(onClick = { vm.refresh() }, enabled = !busy && !state.offline) { Text("Import / refresh music") }
                OutlinedButton(onClick = openLibrary) { Text("Open library to play") }
            } }
        }
        Button(onClick = { vm.signIn(::browser) }, enabled = !busy && !state.offline, modifier = Modifier.fillMaxWidth()) {
            Text("Sign in with Plex")
        }
        Text("Plex opens in your browser. After authorizing, return to this app to select your server. Your Plex password stays with Plex.", fontSize = 12.sp)
        if (signingIn) {
            if (loginUrl.isNotBlank()) OutlinedButton(onClick = { runCatching { browser(loginUrl) }.onFailure { vm.message.value = "No browser is available. Install a browser or use Advanced connection." } }) { Text("Reopen Plex sign-in") }
            TextButton(onClick = { vm.cancelSignIn() }) { Text("Cancel sign-in") }
        }
        servers.forEach { server ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                Text(server.name, fontWeight = FontWeight.Bold)
                Button(onClick = { vm.connectServer(server) }, enabled = !busy && !state.offline) { Text("Connect & import music") }
            } }
        }
        HorizontalDivider()
        TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced connection" else "Advanced connection (server URL + token)") }
        if (advanced) {
            Text("Use this if browser sign-in is unavailable or you need a specific server address. Prefer HTTPS; HTTP is available for trusted local networks.", fontSize = 12.sp)
            OutlinedTextField(url, { url = it }, label = { Text("Plex server URL") }, placeholder = { Text("http://192.168.1.100:32400") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(token, { token = it }, label = { Text("X-Plex-Token") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { vm.connect(url, token) }, enabled = !busy && !state.offline && url.isNotBlank() && token.isNotBlank()) { Text("Connect & import music") }
            Text("Find a token in Plex Web: open a media item → Get Info → View XML, then find X-Plex-Token in the address. Keep it private.", fontSize = 12.sp)
        }
        HorizontalDivider()
        Text("Want to listen offline?", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("Choose a music folder, then use download buttons on tracks, artists, albums or genres. You can also copy your own audio into this folder and scan it in Settings.", fontSize = 13.sp)
        OutlinedButton(onClick = chooseFolder, enabled = !busy && state.downloads.isEmpty()) { Text("Choose download folder") }
        Text("Importing and connecting never sync queued ratings or delete music.", fontSize = 12.sp)
    }
}
