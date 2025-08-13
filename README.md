In MainActivity.kt, “private val clientId” and “private val redirectUri” should be obtained from your sporify bash boad.
In strings.xml, <string name="sample_track_uri">spotify:playlist:"????" </string> of "????" with the string of the playlist you want to play.

Create app/libs and place spotify-app-remote-release-0.8.0.aar in the libs file.
↓ download url 
https://github.com/spotify/android-sdk/releases

Create /app/src/main/res/raw and place beep.wav in the raw file.
The beep.wav is the sound that plays halfway through the song.
