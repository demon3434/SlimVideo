package com.example.slimvideo.utils

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

object Mp4Merger {
    private const val TAG = "Mp4Merger"

    fun merge(files: List<File>, startTimesMs: List<Long>, outputFile: File) {
        if (files.isEmpty()) return
        if (files.size == 1) {
            files[0].copyTo(outputFile, overwrite = true)
            Log.d(TAG, "Single file input, direct copy completed.")
            return
        }

        Log.d(TAG, "Starting merge of ${files.size} segments into ${outputFile.name}")
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        
        var videoTrackIdxDest = -1
        var audioTrackIdxDest = -1
        
        // Find track format from the first file that contains it
        var firstVideoFormatFileIdx = -1
        var firstAudioFormatFileIdx = -1
        
        for (i in files.indices) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(files[i].absolutePath)
                for (t in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(t)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("video/") && firstVideoFormatFileIdx == -1) {
                        firstVideoFormatFileIdx = i
                    } else if (mime.startsWith("audio/") && firstAudioFormatFileIdx == -1) {
                        firstAudioFormatFileIdx = i
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read track metadata from ${files[i].name}", e)
            } finally {
                extractor.release()
            }
        }

        // Add tracks to destination muxer
        if (firstVideoFormatFileIdx != -1) {
            val extractor = MediaExtractor()
            extractor.setDataSource(files[firstVideoFormatFileIdx].absolutePath)
            for (t in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(t)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoTrackIdxDest = muxer.addTrack(format)
                    Log.d(TAG, "Added destination video track from file $firstVideoFormatFileIdx")
                    break
                }
            }
            extractor.release()
        }

        if (firstAudioFormatFileIdx != -1) {
            val extractor = MediaExtractor()
            extractor.setDataSource(files[firstAudioFormatFileIdx].absolutePath)
            for (t in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(t)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIdxDest = muxer.addTrack(format)
                    Log.d(TAG, "Added destination audio track from file $firstAudioFormatFileIdx")
                    break
                }
            }
            extractor.release()
        }

        muxer.start()
        Log.d(TAG, "Muxer started.")

        val buffer = ByteBuffer.allocate(2 * 1024 * 1024) // 2MB buffer for keyframes
        val bufferInfo = MediaCodec.BufferInfo()

        for (idx in files.indices) {
            val file = files[idx]
            val segmentStartMs = startTimesMs[idx]
            val offsetUs = segmentStartMs * 1000L
            Log.d(TAG, "Merging segment $idx: ${file.name} (Start offset: ${segmentStartMs}ms / ${offsetUs}us)")

            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                
                var localVideoTrackIdx = -1
                var localAudioTrackIdx = -1
                
                for (t in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(t)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("video/")) {
                        localVideoTrackIdx = t
                    } else if (mime.startsWith("audio/")) {
                        localAudioTrackIdx = t
                    }
                }

                // Write video samples
                if (localVideoTrackIdx != -1 && videoTrackIdxDest != -1) {
                    extractor.selectTrack(localVideoTrackIdx)
                    var videoSampleCount = 0
                    while (true) {
                        bufferInfo.offset = 0
                        bufferInfo.size = extractor.readSampleData(buffer, 0)
                        if (bufferInfo.size < 0) {
                            break
                        }
                        bufferInfo.presentationTimeUs = extractor.sampleTime + offsetUs
                        bufferInfo.flags = extractor.sampleFlags
                        
                        muxer.writeSampleData(videoTrackIdxDest, buffer, bufferInfo)
                        videoSampleCount++
                        extractor.advance()
                    }
                    extractor.unselectTrack(localVideoTrackIdx)
                    Log.d(TAG, "Wrote $videoSampleCount video samples for segment $idx")
                }

                // Write audio samples
                if (localAudioTrackIdx != -1 && audioTrackIdxDest != -1) {
                    extractor.selectTrack(localAudioTrackIdx)
                    var audioSampleCount = 0
                    while (true) {
                        bufferInfo.offset = 0
                        bufferInfo.size = extractor.readSampleData(buffer, 0)
                        if (bufferInfo.size < 0) {
                            break
                        }
                        bufferInfo.presentationTimeUs = extractor.sampleTime + offsetUs
                        bufferInfo.flags = extractor.sampleFlags
                        
                        muxer.writeSampleData(audioTrackIdxDest, buffer, bufferInfo)
                        audioSampleCount++
                        extractor.advance()
                    }
                    extractor.unselectTrack(localAudioTrackIdx)
                    Log.d(TAG, "Wrote $audioSampleCount audio samples for segment $idx")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error processing segment $idx (${file.name})", e)
                throw e
            } finally {
                extractor.release()
            }
        }

        try {
            muxer.stop()
            Log.d(TAG, "Muxer stopped.")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop muxer cleanly", e)
        } finally {
            muxer.release()
            Log.d(TAG, "Muxer released. Merge complete.")
        }
    }
}
