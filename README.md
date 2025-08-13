In MainActivity.kt, “private val clientId” and “private val redirectUri” should be obtained from your sporify bash boad.<br>
In strings.xml, <string name="sample_track_uri">spotify:playlist:"????" </string> of "????" with the string of the playlist you want to play.<br>

Create app/libs and place spotify-app-remote-release-0.8.0.aar in the libs file.<br>
↓ download url <br>
https://github.com/spotify/android-sdk/releases<br>

Create /app/src/main/res/raw and place beep.wav in the raw file.<br>
The beep.wav is the sound that plays halfway through the song.<br>
