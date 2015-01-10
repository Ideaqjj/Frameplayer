package com.zepp.frameplayer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaCodec.BufferInfo;
import android.util.Log;
import android.view.Surface;

import static java.lang.Thread.sleep;

public class FramePlayer {

    public interface OnPreparedListener
    {
        void onPrepared(FramePlayer fp);
    }
    public interface OnVideoSizeChangedListener
    {
        void onVideoSizeChanged(FramePlayer fp, int width, int height);
    }

    public interface OnErrorListener
    {
        boolean onError(FramePlayer fp, int what, int extra);
    }
    public interface OnSeekCompleteListener
    {
        void onSeekComplete(FramePlayer fp);
    }
    public interface OnCompletionListener
    {
        void onCompletion(FramePlayer fp);
    }
    public interface OnPositionUpdateListener
    {
        void onPositionUpdate(FramePlayer fp, int currentPosition);
    }

    /**
     * Create FramePlayer
     *
     * @param surface that is used for rendering
     * @param source that specifies the video path
     */
    public static FramePlayer createPlayer(Surface surface, String source){
        try{
            FramePlayer player = new FramePlayer();
            player.setDataSource(source);
            player.setSurface(surface);
            player.prepare();
            return player;
        }catch (Exception e){
            return null;
        }
    }

    public static final int FRAMEPLAYER_ERROR_UNKNOWN = 1;
    public static final int FRAMEPLAYER_ERROR_SERVER_DIED = 100;
    public static final int FRAMEPLAYER_ERROR_IO = -1004;
    public static final int FRAMEPLAYER_ERROR_UNSUPPORTED = -1010;
    public static final int FRAMEPLAYER_ERROR_TIMED_OUT = -110;

    private enum PlayerState{
        Idle,
        Initialized,
        Preparing,
        Prepared,
        Started,
        Paused,
        Stopped,
        PlaybackCompleted,
        End,
        Error,

    }
    private enum MessageType {
        Play, Pause, Seek, Stop, Reset, Release
    }

    private class PlayerMessage {
        public MessageType messageType;
        public long content;
    }

    private static final int TIMEOUT_USEC = 20000;
    private static final String TAG = "FramePlayer";
    private static final String VIDEO_PREFIX_IN_MIME = "video/";

    //region Private members
    private BufferInfo mCurFrameInfo = new BufferInfo(); // save information of current frame
    private boolean mIsExtractorReachedEOS; // indicate if reach the end of stream
    private volatile long mCurPresentationTimeUs;
    private volatile long mSeekTargetTimeUs;
    private Object mObjForSeekSync = new Object();
    private long mEstimatedKeyframeInterval;
    private long mIdenticalFrameInterval;
    private long mLastRenderingTimeUs;

    private BlockingQueue<PlayerMessage> mCtrlMsgQueue;
    private MediaExtractor mExtractor;
    private MediaCodec mDecoder;
    private Surface mSurface;
    private Thread mWorkerThread;
    private Thread mPrepareThread;

    private String mSource;
    private volatile PlayerState mState;
    private volatile boolean mIsStopPlayback;
    private MediaInfoExtractor.MediaInfo mMediaInfo;
    private String mMime;
    private MediaFormat mFormat;

    private OnPositionUpdateListener mOnPositionUpdateListener;
    private OnCompletionListener mOnCompletionListener;
    private OnErrorListener mOnErrorListener;
    private OnSeekCompleteListener mOnSeekCompleteListener;
    private OnVideoSizeChangedListener mOnVideoSizeChangedListener;
    private OnPreparedListener mOnPreparedListener;
    //endregion

    //region Constructor
    public FramePlayer() {
        mCtrlMsgQueue = new LinkedBlockingDeque<PlayerMessage>();
        changeStateTo(PlayerState.Idle);
    }
    //endregion
    //region Callback
    public void setOnPositionUpdateListener(OnPositionUpdateListener listener){
        mOnPositionUpdateListener = listener;
    }
    public void setOnSeekCompleteListener(OnSeekCompleteListener listener){
        mOnSeekCompleteListener = listener;
    }
    public void setOnCompletionListener(OnCompletionListener listener){
        mOnCompletionListener = listener;
    }
    public void setOnErrorListener(OnErrorListener listener)
    {
        mOnErrorListener = listener;
    }
    public void setOnVideoSizeChangedListener(OnVideoSizeChangedListener listener)
    {
        mOnVideoSizeChangedListener = listener;
    }
    public void setOnPreparedListener(OnPreparedListener listener)
    {
        mOnPreparedListener = listener;
    }
    //endregion

    //region Public interface
    public void setSurface(Surface surface) throws NullPointerException{
        if(surface == null){
            throw new NullPointerException("Invalid argument.");
        }
        mSurface = surface;
    }

    public void setDataSource(String source) throws IOException, NullPointerException, IllegalArgumentException{
        if(source == null)
            throw new NullPointerException("The source is null.");
        if(source.isEmpty())
            throw new IllegalArgumentException("Source cannot be empty.");
        if(mState != PlayerState.Idle)
            throw new IllegalStateException("Cannot set source in current state");

        mSource = source;
        changeStateTo(PlayerState.Initialized);
    }

    public void prepareAsync() throws IllegalStateException{
        if(mSurface == null){
            throw new IllegalStateException("Surface is null, call setSurface first.");
        }
        if(mState != PlayerState.Initialized
                && mState != PlayerState.Stopped){
            throw new IllegalStateException("Prepare operation is invalid in current state.");
        }
        mPrepareThread = new Thread("PrepareAsnycWorker"){
            @Override
            public void run(){
                try {
                    changeStateTo(PlayerState.Preparing);
                    prepareInternal();
                }catch (IOException ioe) {
                    ioe.printStackTrace();
                    onError(FRAMEPLAYER_ERROR_SERVER_DIED, FRAMEPLAYER_ERROR_IO);
                }catch(Exception e){
                    e.printStackTrace();
                    onError(FRAMEPLAYER_ERROR_SERVER_DIED, FRAMEPLAYER_ERROR_UNSUPPORTED);
                }
                Log.d(TAG, "[FramePlayer]: prepare thread exited.");
            }
        };
        mPrepareThread.start();
    }

    public void prepare() throws IllegalStateException, IOException{
        if(mSurface == null){
            throw new IllegalStateException("Surface is null, call setSurface first.");
        }
        if(mState != PlayerState.Initialized
                && mState != PlayerState.Stopped){
            throw new IllegalStateException("Prepare operation is invalid in current state.");
        }

        prepareInternal();
    }
    public void start() throws IllegalStateException{
        if(mState == PlayerState.Started){
            return;
        }

        if(mState == PlayerState.Prepared
                || mState == PlayerState.Paused
                || mState == PlayerState.PlaybackCompleted){
            PlayerMessage message = new PlayerMessage();
            message.messageType = MessageType.Play;
            pushMessage(message);
        }else{
            throw new IllegalStateException();
        }
    }
    public void stop() throws IllegalStateException {
        if(mState == PlayerState.Stopped){
            return;
        }

        if (mState == PlayerState.Prepared
                || mState == PlayerState.Started
                || mState == PlayerState.Paused
                || mState == PlayerState.PlaybackCompleted){
            PlayerMessage message = new PlayerMessage();
            message.messageType = MessageType.Stop;
            pushMessage(message);
            waitAllBGThreadsExit();
        }else{
            throw new IllegalStateException("Cannot change to stopped state.");
        }

    }
    public void pause() throws IllegalStateException{
        if(mState == PlayerState.Paused){
            return;
        }

        if(mState == PlayerState.Started
                || mState == PlayerState.PlaybackCompleted){
            PlayerMessage message = new PlayerMessage();
            message.messageType = MessageType.Pause;
            pushMessage(message);
        }else{
            throw new IllegalStateException();
        }
    }

    public void seekTo(long msec){
        if( !canSeeking() ){
            throw new IllegalStateException("Cannot handle seeking request in current state.");
        }

        long usec = msec * 1000; // to microsecond
        if(usec < 0){
            usec = 0;
        }
        else if(usec > mMediaInfo.durationUs){
            usec = mMediaInfo.durationUs;
        }
        onPositionUpdate(usec);
        synchronized (mObjForSeekSync){
            mSeekTargetTimeUs = usec;
            PlayerMessage message = new PlayerMessage();
            message.messageType = MessageType.Seek;
            message.content = usec;
            pushMessage(message);
        }
    }
    public void seekToWithoutCallback(long msec){
        if( !canSeeking() ){
            throw new IllegalStateException("Cannot handle seeking request in current state.");
        }

        long usec = msec * 1000; // to microsecond
        if(usec < 0){
            usec = 0;
        }
        else if(usec > mMediaInfo.durationUs){
            usec = mMediaInfo.durationUs;
        }
        synchronized (mObjForSeekSync){
            mSeekTargetTimeUs = usec;
            PlayerMessage message = new PlayerMessage();
            message.messageType = MessageType.Seek;
            message.content = usec;
            pushMessage(message);
        }
    }
    public void reset(){
        if(mState == PlayerState.Idle)
            return;

        PlayerMessage message = new PlayerMessage();
        message.messageType = MessageType.Reset;
        pushMessage(message);
        waitAllBGThreadsExit();
    }
    public void release(){
        if(mState == PlayerState.End){
            return;
        }
        PlayerMessage message = new PlayerMessage();
        message.messageType = MessageType.Release;
        pushMessage(message);
        waitAllBGThreadsExit();
    }

    public int getVideoWidth(){
        if(mMediaInfo != null){
            return (int)mMediaInfo.width;
        }
        return 0;
    }
    public int getVideoHeight(){
        if(mMediaInfo != null){
            return (int)mMediaInfo.height;
        }
        return 0;
    }
    public int getVideoRotation(){
        if(mMediaInfo != null){
            return mMediaInfo.rotation;
        }
        return -1;
    }
    public long getPerSampleDuration(){
        if(mMediaInfo != null){
            return (int)mMediaInfo.perFrameDurationUs / 1000; // to millisecond
        }
        return -1;
    }
    public long getDuration(){
        if(mMediaInfo != null){
            return (int)mMediaInfo.durationUs / 1000; // to millisecond
        }
        return -1;
    }
    public long getCurrentPosition(){
        return Math.max(mCurPresentationTimeUs / 1000, 0);
    }
    public boolean isReachEOS(){return isDecoderReachEOS();}
    public boolean isPlaying(){
        return mState == PlayerState.Started;
    }
    //endregion

    //region Private methods
    private void onError(int what, int extra){
        if(mOnErrorListener != null
                && mOnErrorListener.onError(this, what, extra)){
            return;
        }
        changeStateTo(PlayerState.Error);
    }
    private void prepareInternal() throws IOException{
        mMediaInfo = MediaInfoExtractor.extract(mSource);
        mEstimatedKeyframeInterval = mMediaInfo.perFrameDurationUs * mMediaInfo.fps;
        mIdenticalFrameInterval = (long)(mMediaInfo.perFrameDurationUs * 0.5);
        mExtractor = new MediaExtractor();
        mExtractor.setDataSource(mSource);

        int videoTrackIndex = selectVideoTrack(mExtractor);
        if(videoTrackIndex < 0)
            throw new IOException("Can't find video info!");

        mExtractor.selectTrack(videoTrackIndex);
        mFormat = mExtractor.getTrackFormat(videoTrackIndex);
        // the MediaCodec has build-in support for rotation from 5.0, but we want to do this by ourselves for
        // better control of UI layout. So we set the rotation to 0 to tell MediaCodec don't do anything with rotation.
        try{
            Integer rotation = mFormat.getInteger("rotation-degrees");
            mFormat.setInteger("rotation-degrees", 0);
        }catch (Exception ex){}
        mMime = mFormat.getString(MediaFormat.KEY_MIME);

        restartDecoder();
        resetPositionInfo();

        mIsStopPlayback = false;
        mWorkerThread = new Thread("FramePlayerThread"){
            @Override
            public void run() {
                try {
                    workLoop();
                }catch (InterruptedException ie){
                    ie.printStackTrace();
                } catch (Exception e) {
                    e.printStackTrace();
                    onError(FRAMEPLAYER_ERROR_SERVER_DIED, FRAMEPLAYER_ERROR_UNKNOWN);
                    assert false;
                }
                Log.d(TAG, "exit playback loop.");
            }
        };
        mWorkerThread.start();
        changeStateTo(PlayerState.Prepared);
    }
    private void restartDecoder() {
        if(mDecoder != null){
            mDecoder.stop();
            mDecoder.release();
            mDecoder = null;
        }
        mDecoder = MediaCodec.createDecoderByType(mMime);
        mDecoder.configure(mFormat, mSurface, null, 0);
        mDecoder.start();
    }
    private int selectVideoTrack(MediaExtractor extractor){
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime.startsWith(VIDEO_PREFIX_IN_MIME)) {
                return i;
            }
        }
        return -1;
    }
    private void extractorSeekTo(long timestamp, int seekFlag){
        mExtractor.seekTo(timestamp, seekFlag);
        mIsExtractorReachedEOS = false;
    }
    private void workLoop() throws InterruptedException{
        while (canLoopContinue()) {
            PlayerMessage message = mCtrlMsgQueue.poll();

            if(message == null){
                if(mState == PlayerState.Started){
                    playback();
                    continue;
                }
                // in pause state, wait for command
                message = mCtrlMsgQueue.take();
            }

            if(message.messageType == MessageType.Seek) {
                PlayerMessage nextMessage = mCtrlMsgQueue.peek();
                if (nextMessage != null && nextMessage.messageType == MessageType.Seek) {
                    if(nextMessage.content < message.content)
                        continue; // only ignore pending back seeking message.
                }
            }
            processMessage(message);
        }
    }
    private void putOneFrameToDecoder() {
        if (mIsExtractorReachedEOS)
            return;

        ByteBuffer[] inputBuffers = mDecoder.getInputBuffers();
        int inIndex = mDecoder.dequeueInputBuffer(TIMEOUT_USEC);
        if (inIndex >= 0) {
            ByteBuffer buffer = inputBuffers[inIndex];
            int sampleSize = mExtractor.readSampleData(buffer, 0);
            if (sampleSize < 0) {
                Log.d(TAG, "InputBuffer BUFFER_FLAG_END_OF_STREAM");
                mDecoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                mIsExtractorReachedEOS = true;
            } else {
                mDecoder.queueInputBuffer(inIndex, 0, sampleSize, mExtractor.getSampleTime(), 0);
                mExtractor.advance();
            }
        }
    }
    private int takeOneFrameFromDecoder() {
        int outIndex = mDecoder.dequeueOutputBuffer(mCurFrameInfo, TIMEOUT_USEC);
        switch (outIndex) {
            case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                Log.d(TAG, "INFO_OUTPUT_BUFFERS_CHANGED");
                break;
            case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                MediaFormat newFormat = mDecoder.getOutputFormat();
                Log.d(TAG, "New format " + newFormat);
                break;
            case MediaCodec.INFO_TRY_AGAIN_LATER:
                break;
            default:
                break;
        }
        return outIndex;
    }
    private boolean canLoopContinue(){
        return !mIsStopPlayback;
    }
    private boolean canSeeking(){
        return ( mState == PlayerState.Prepared
                || mState == PlayerState.Started
                || mState == PlayerState.Paused
                || mState == PlayerState.PlaybackCompleted);
    }
    private boolean currentFrameIsTargetFrame(long currentTimestamp, long targetTimestamp){
        return Math.abs(currentTimestamp - targetTimestamp) < mIdenticalFrameInterval;
    }
    private boolean isDecoderReachEOS(){
        return ((mCurFrameInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0);
    }
    private void resetPositionInfo() {
        mCurPresentationTimeUs = Integer.MIN_VALUE;
        mCurFrameInfo = new BufferInfo();
        onPositionUpdate(0);
    }
    private void pushMessage(PlayerMessage message) {
        try {
            mCtrlMsgQueue.put(message);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
    private void processMessage(PlayerMessage message){
        switch (message.messageType){
            case Pause:
                pauseInternal();
                break;
            case Play:
                startInternal();
                break;
            case Seek:
                seekInternal(message.content);
                break;
            case Stop:
                stopInternal();
                break;
            case Reset:
                resetInternal();
                break;
            case Release:
                releaseInternal();
                break;
            default:
                assert false;
                break;
        }
    }
    private void onPlaybackComplete(){
        mCurPresentationTimeUs = mMediaInfo.durationUs;
        onPositionUpdate(mCurPresentationTimeUs);
        changeStateTo(PlayerState.PlaybackCompleted);
    }
    private void changeStateTo(PlayerState state){
        mState = state;
        switch (mState){
            case PlaybackCompleted:
                onCompletion();
                break;
            case Prepared:
                onPrepared();
                break;
            default:
                return;
        }
    }
    private void onPositionUpdate(long currentPosition){
        if(mOnPositionUpdateListener != null){
            mOnPositionUpdateListener.onPositionUpdate(this, (int)currentPosition/1000); // to millisecond
        }
    }
    private void onCompletion(){
        if(mOnCompletionListener != null)
            mOnCompletionListener.onCompletion(this);
    }
    private void onPrepared(){
        if(mOnPreparedListener != null)
            mOnPreparedListener.onPrepared(this);
    }
    //endregion

    //region Internal operations
    private void playback(){
        // two cases: 1. play to EOS. 2. user seek to EOS.
        if(isDecoderReachEOS()){
            onPlaybackComplete();
        }else{
            long prevFrameTime = mCurFrameInfo.presentationTimeUs;
            putOneFrameToDecoder();
            int bufferIndex = takeOneFrameFromDecoder();
            if(bufferIndex >= 0){
                long elapsedUs = System.nanoTime() / 1000 - mLastRenderingTimeUs;
                long timeToBeWait = (mCurFrameInfo.presentationTimeUs - (prevFrameTime + elapsedUs)) / 1000;
                if (timeToBeWait > 0) {
                    try {
                        sleep(timeToBeWait);
                    }catch (InterruptedException iex){} // safe ignore
                }
                mDecoder.releaseOutputBuffer(bufferIndex, true);
                mLastRenderingTimeUs = System.nanoTime() / 1000;
                if(isDecoderReachEOS()){
                    onPlaybackComplete();
                }else{
                    mCurPresentationTimeUs = mCurFrameInfo.presentationTimeUs;
                    onPositionUpdate(mCurPresentationTimeUs);
                }
            }
        }
    }
    private void seekInternal(long timestamp){
        if(currentFrameIsTargetFrame(mCurPresentationTimeUs, timestamp)){
            return;
        }
        // we can think that the seeking is atomical, so we set mCurPresentationTimeUs to target timestamp first
        // to make getCurrentPosition return right value
        mCurPresentationTimeUs = timestamp;

        long seekInterval = timestamp - mCurFrameInfo.presentationTimeUs;
        // in some devices, the presentation time of last decoded frame will be set to 0. for
        // this case, we need do seeking too.
        if(seekInterval < 0
                || (isDecoderReachEOS() && mCurFrameInfo.presentationTimeUs == 0)) { // back seeking
            extractorSeekTo(timestamp, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            // there is a bug in MediaCodec now, you need to create a new decoder if the stream reached the end.
            // Or the decoder cannot generate output after several seeking.
            if(isDecoderReachEOS()){
                restartDecoder();
            }else{
                mDecoder.flush();
            }

        }else{
            //should forward seeking
            if(isDecoderReachEOS()) // already reach EOS.
                return;

            // only do forward seeking if the request timestamp advanced the presentation time of next key frame.
            if(seekInterval > mEstimatedKeyframeInterval){
                extractorSeekTo(timestamp, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                mDecoder.flush();
            }
        }

        while (canLoopContinue()){
            putOneFrameToDecoder();
            int bufferIndex = takeOneFrameFromDecoder();
            if( bufferIndex >= 0 ) {
                boolean seekCompleted = false;
                // target frame is found.
                synchronized (mObjForSeekSync){
                    if (currentFrameIsTargetFrame(mCurFrameInfo.presentationTimeUs, mSeekTargetTimeUs)) {
                        while (true) {
                            PlayerMessage message = mCtrlMsgQueue.peek();
                            if (message != null && message.messageType == MessageType.Seek
                                    && message.content > mSeekTargetTimeUs){
                                mCtrlMsgQueue.poll();
                            }else{
                                break;
                            }
                        }
                        mCurPresentationTimeUs = mCurFrameInfo.presentationTimeUs;
                        seekCompleted = true;
                    }
                }
                if(!seekCompleted){
                    if(currentFrameIsTargetFrame(mCurFrameInfo.presentationTimeUs, timestamp)){
                        mCurPresentationTimeUs = mCurFrameInfo.presentationTimeUs;
                        seekCompleted = true;
                    }else if(isDecoderReachEOS()){
                        mCurPresentationTimeUs = mMediaInfo.durationUs;
                        seekCompleted = true;
                    }
                }
                if(seekCompleted){
//                    onPositionUpdate(mCurPresentationTimeUs);
                    mDecoder.releaseOutputBuffer(bufferIndex, true);
                    if(mOnSeekCompleteListener != null)
                        mOnSeekCompleteListener.onSeekComplete(this);
                    return;
                }else{
                    mDecoder.releaseOutputBuffer(bufferIndex, false); // don't render to surface
                }
            }
        }
    }
    private void pauseInternal(){
        changeStateTo(PlayerState.Paused);
    }
    private void startInternal(){
        if(isDecoderReachEOS()){
            Log.d(TAG, "Play from beginning");
            extractorSeekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            restartDecoder();
            resetPositionInfo();
        }
        changeStateTo(PlayerState.Started);
    }
    private void stopInternal(){
        stopComponent();
        changeStateTo(PlayerState.Stopped);
    }
    private void resetInternal(){
        stopComponent();
        resetStuff();
        changeStateTo(PlayerState.Idle);
    }
    private void releaseInternal(){
        resetInternal();
        changeStateTo(PlayerState.End);
    }
    private void stopComponent(){
        mIsStopPlayback = true;
        if(mPrepareThread != null){
            mPrepareThread.interrupt();
        }
        if(mWorkerThread != null){
            mWorkerThread.interrupt();
        }
        if(mDecoder!=null) {
            mDecoder.stop();
            mDecoder.release();
            mDecoder = null;
        }
        if(mExtractor!=null){
            mExtractor.release();
            mExtractor = null;
        }
        mCtrlMsgQueue.clear();
        mMediaInfo = null;
    }
    private void resetStuff(){
        mSource = "";
        mOnCompletionListener = null;
        mOnSeekCompleteListener = null;
        mOnErrorListener = null;
    }
    private void waitAllBGThreadsExit(){
        try{
            if(mPrepareThread !=null){
                mPrepareThread.join();
                mPrepareThread = null;
                Log.d(TAG, "[FramePlayer]: prepare thread join done.");
            }
        }catch (Exception ex){}
        try{
            mWorkerThread.join();
            mWorkerThread = null;
            Log.d(TAG, "[FramePlayer]: worker thread join done.");
        }catch (Exception ex){}
    }
    //endregion

}

