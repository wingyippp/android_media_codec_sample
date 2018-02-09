package com.tencent.mediacodecsample;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Environment;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.nio.ByteBuffer;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int TIMEOUT_US = 1000;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        findViewById(R.id.tv_word).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new Thread() {
                    @Override
                    public void run() {
                        try {
                            initMediaCodec();
                        } catch (Exception ignored) {
                            Log.e(TAG, "exception", ignored);
                        }
                    }
                }.start();
            }
        });
        ensurePermission();
    }

    protected void ensurePermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    1);
        }
    }

    private void initMediaCodec() throws Exception {
        String srcPath = Environment.getExternalStorageDirectory().getPath() + "/qqmusic/songs/chu_yin_wei_lai.mp3";
        File file = new File(srcPath);
        FileInputStream fis = new FileInputStream(file);
        FileDescriptor fd = fis.getFD();
        MediaCodec mediaDecode = null;
        MediaExtractor mediaExtractor = new MediaExtractor();//此类可分离视频文件的音轨和视频轨道
        int mSampleRate = 0;
        mediaExtractor.setDataSource(fd);
        for (int i = 0; i < mediaExtractor.getTrackCount(); i++) {//遍历媒体轨道 此处我们传入的是音频文件，所以也就只有一条轨道
            MediaFormat format = mediaExtractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime.startsWith("audio")) {//获取音频轨道
                mediaExtractor.selectTrack(i);//选择此音频轨道
                mediaDecode = MediaCodec.createDecoderByType(mime);//创建Decode解码器
                mediaDecode.configure(format, null, null, 0);
                mSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                break;
            }
        }
        if (mediaDecode != null) {
            mediaDecode.start();//启动MediaCodec ，等待传入数据
            ByteBuffer[] decodeInputBuffers = mediaDecode.getInputBuffers();//MediaCodec在此ByteBuffer[]中获取输入数据
            ByteBuffer[] decodeOutputBuffers = mediaDecode.getOutputBuffers();//MediaCodec将解码后的数据放到此ByteBuffer[]中 我们可以直接在这里面得到PCM数据
            MediaCodec.BufferInfo decodeBufferInfo = new MediaCodec.BufferInfo();//用于描述解码得到的byte[]数据的相关信息
            int buffSize = AudioTrack.getMinBufferSize(mSampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            AudioTrack audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, mSampleRate,
                    AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    buffSize,
                    AudioTrack.MODE_STREAM);
            audioTrack.play();
            while (true) {
                int inIndex = mediaDecode.dequeueInputBuffer(TIMEOUT_US);
                if (inIndex >= 0) {
                    ByteBuffer buffer = decodeInputBuffers[inIndex];
                    int sampleSize = mediaExtractor.readSampleData(buffer, 0);
                    if (sampleSize < 0) {
                        // We shouldn't stop the playback at this point, just pass the EOS
                        // flag to mDecoder, we will get it again from the
                        // dequeueOutputBuffer
                        Log.d("DecodeActivity", "InputBuffer BUFFER_FLAG_END_OF_STREAM");
                        mediaDecode.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);

                    } else {
                        mediaDecode.queueInputBuffer(inIndex, 0, sampleSize, mediaExtractor.getSampleTime(), 0);
                        mediaExtractor.advance();
                    }

                    int outIndex = mediaDecode.dequeueOutputBuffer(decodeBufferInfo, TIMEOUT_US);
                    switch (outIndex) {
                        case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                            Log.d("DecodeActivity", "INFO_OUTPUT_BUFFERS_CHANGED");
                            decodeOutputBuffers = mediaDecode.getOutputBuffers();
                            break;

                        case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                            MediaFormat format = mediaDecode.getOutputFormat();
                            Log.d("DecodeActivity", "New format " + format);
                            audioTrack.setPlaybackRate(format.getInteger(MediaFormat.KEY_SAMPLE_RATE));

                            break;
                        case MediaCodec.INFO_TRY_AGAIN_LATER:
                            Log.d("DecodeActivity", "dequeueOutputBuffer timed out!");
                            break;

                        default:
                            ByteBuffer outBuffer = decodeOutputBuffers[outIndex];
                            Log.v("DecodeActivity", "We can't use this buffer but render it due to the API limit, " + outBuffer);

                            final byte[] chunk = new byte[decodeBufferInfo.size];
                            outBuffer.get(chunk); // Read the buffer all at once
                            outBuffer.clear(); // ** MUST DO!!! OTHERWISE THE NEXT TIME YOU GET THIS SAME BUFFER BAD THINGS WILL HAPPEN

                            audioTrack.write(chunk, decodeBufferInfo.offset, decodeBufferInfo.offset + decodeBufferInfo.size); // AudioTrack write data
                            mediaDecode.releaseOutputBuffer(outIndex, false);
                            break;
                    }

                    // All decoded frames have been rendered, we can stop playing now
                    if ((decodeBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.d("DecodeActivity", "OutputBuffer BUFFER_FLAG_END_OF_STREAM");
                        break;
                    }
                }
            }
        }
    }
}