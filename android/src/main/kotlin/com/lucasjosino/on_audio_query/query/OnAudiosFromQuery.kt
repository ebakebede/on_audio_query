package com.lucasjosino.on_audio_query.query

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lucasjosino.on_audio_query.query.helper.OnAudioHelper
import com.lucasjosino.on_audio_query.types.checkAudiosFromType
import com.lucasjosino.on_audio_query.types.songProjection
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** OnAudiosFromQuery */
class OnAudiosFromQuery : ViewModel() {

    //Main parameters
    private val helper = OnAudioHelper()
    private val uri: Uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    private var pId = 0
    private var pUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

    @SuppressLint("StaticFieldLeak")
    private lateinit var context: Context
    private lateinit var where: String
    private lateinit var whereVal: String
    private lateinit var resolver: ContentResolver

    //
    fun querySongsFrom(context: Context, result: MethodChannel.Result, call: MethodCall) {
        this.context = context; resolver = context.contentResolver

        //
        val helper = call.argument<Int>("type")!!

        //TODO Add a better way to handle this query
        //This will fix (for now) the problem between Android < 30 && Android > 30
        //The method used to query genres on Android < 30 don't work properly on Android > 30 so,
        //we need separate
        //
        //If helper == 6 (Playlist) send to [querySongsFromPlaylistOrGenre] in any version.
        //If helper == 4 (Genre name) && helper == 5 (Genre Id) and Android < 30 send to
        //[querySongsFromPlaylistOrGenre] else, follow the rest of the "normal" code.
        //
        //Why? Android 10 and below don't has "genre" category and we need use a "workaround"
        //[MediaStore](https://developer.android.com/reference/android/provider/MediaStore.Audio.AudioColumns#GENRE)
        if (helper == 6 || ((helper == 4 || helper == 5) && Build.VERSION.SDK_INT < 30)) {
            //Works on Android 10
            querySongsFromPlaylistOrGenre(result, call, helper)
        } else {
            //Works on Android 11
            //where -> Album/Artist/Genre(Sometimes) ; where -> uri
            whereVal = call.argument<Any>("where")!!.toString()
            where = checkAudiosFromType(helper)

            //Query everything in the Background it's necessary for better performance
            viewModelScope.launch {
                //Start querying
                val resultSongList = loadSongsFrom()

                //Flutter UI will start, but, information still loading
                result.success(resultSongList)
            }
        }
    }

    //Loading in Background
    private suspend fun loadSongsFrom(): ArrayList<MutableMap<String, Any?>> =
        withContext(Dispatchers.IO) {
            val cursor = resolver.query(uri, songProjection, where, arrayOf(whereVal), null)
            val songsFromList: ArrayList<MutableMap<String, Any?>> = ArrayList()
            while (cursor != null && cursor.moveToNext()) {
                val tempData: MutableMap<String, Any?> = HashMap()
                for (audioMedia in cursor.columnNames) {
                    tempData[audioMedia] = helper.loadSongItem(audioMedia, cursor)
                }

                //Get a extra information from audio, e.g: extension, uri, etc..
                val tempExtraData = helper.loadSongExtraInfo(uri, tempData)
                tempData.putAll(tempExtraData)

                songsFromList.add(tempData)
            }
            cursor?.close()
            return@withContext songsFromList
        }

    private fun querySongsFromPlaylistOrGenre(
        result: MethodChannel.Result,
        call: MethodCall,
        type: Int
    ) {
        val info = call.argument<Any>("where")!!

        //Check if Playlist exists based in Id
        val checkedName = if (type == 4 || type == 5) {
            checkName(genreName = info.toString())
        } else checkName(plName = info.toString())

        if (!checkedName) pId = info.toString().toInt()

        //
        pUri = if (type == 4 || type == 5) {
            MediaStore.Audio.Genres.Members.getContentUri("external", pId.toLong())
        } else MediaStore.Audio.Playlists.Members.getContentUri("external", pId.toLong())

        //Query everything in the Background it's necessary for better performance
        viewModelScope.launch {
            //Start querying
            val resultSongsFrom = loadSongsFromPlaylistOrGenre()

            //Flutter UI will start, but, information still loading
            result.success(resultSongsFrom)
        }
    }

    private suspend fun loadSongsFromPlaylistOrGenre(): ArrayList<MutableMap<String, Any?>> =
        withContext(Dispatchers.IO) {

            val songsFrom: ArrayList<MutableMap<String, Any?>> = ArrayList()
            val cursor = resolver.query(pUri, songProjection, null, null, null)
            while (cursor != null && cursor.moveToNext()) {
                val tempData: MutableMap<String, Any?> = HashMap()
                for (media in cursor.columnNames) {
                    tempData[media] = helper.loadSongItem(media, cursor)
                }

                //Get a extra information from audio, e.g: extension, uri, etc..
                val tempExtraData = helper.loadSongExtraInfo(uri, tempData)
                tempData.putAll(tempExtraData)

                songsFrom.add(tempData)
            }
            cursor?.close()
            return@withContext songsFrom
        }

    //Return true if playlist or genre exists, false, if don't.
    private fun checkName(plName: String? = null, genreName: String? = null): Boolean {
        val uri: Uri
        val projection: Array<String>

        //
        if (plName != null) {
            uri = MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI
            projection = arrayOf(MediaStore.Audio.Playlists.NAME, MediaStore.Audio.Playlists._ID)
        } else {
            uri = MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI
            projection = arrayOf(MediaStore.Audio.Genres.NAME, MediaStore.Audio.Genres._ID)
        }

        //
        val cursor = resolver.query(uri, projection, null, null, null)
        while (cursor != null && cursor.moveToNext()) {
            val name = cursor.getString(0) //Name

            if (name != null && name == plName || name == genreName) {
                pId = cursor.getInt(1)
                return true
            }
        }
        cursor?.close()
        return false
    }
}

//Extras:

// * All projection used for query audio in this Plugin
//I/OnAudioCursor[Audio]: [
// _data,
// _display_name,
// _id,
// _size,
// album,
// album_artist,
// album_id
// album_key,
// artist,
// artist_id,
// artist_key,
// bookmark,
// composer,
// date_added,
// duration,
// title,
// track,
// year,
// is_alarm
// is_music,
// is_notification,
// is_podcast,
// is_ringtone
// ]