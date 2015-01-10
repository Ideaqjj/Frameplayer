package com.zepp.frameplayer;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.SurfaceTexture;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.MediaController;

import java.io.IOException;


public class FramePlayerView extends TextureView implements MediaController.MediaPlayerControl {
    private String TAG = "FramePlayerView";

    // all possible internal states
    private static final int STATE_ERROR              = -1;
    private static final int STATE_IDLE               = 0;
    private static final int STATE_PREPARING          = 1;
    private static final int STATE_PREPARED           = 2;
    private static final int STATE_PLAYING            = 3;
    private static final int STATE_PAUSED             = 4;
    private static final int STATE_PLAYBACK_COMPLETED = 5;

    // mCurrentState is a FramePlayerView object's current state.
    // mTargetState is the state that a method caller intends to reach.
    // For instance, regardless the FramePlayerView object's current state,
    // calling pause() intends to bring the object to a target state
    // of STATE_PAUSED.
    private int mCurrentState = STATE_IDLE;
    private int mTargetState  = STATE_IDLE;

    // All the stuff we need for playing and showing a video
    private Surface mSurface = null;
    private FramePlayer mFramePlayer = null;
    private int mVideoWidth;
    private int mVideoHeight;
    private int mVideoRotation;

    private int         mSurfaceWidth;
    private int         mSurfaceHeight;
    private MediaController mMediaController;
    private FramePlayer.OnCompletionListener mOnCompletionListener;
    private FramePlayer.OnPreparedListener mOnPreparedListener;
    private FramePlayer.OnErrorListener mOnErrorListener;

    private int         mSeekWhenPrepared;  // recording the seek position while preparing
    private boolean     mCanPause;
    private boolean     mCanSeekBack;
    private boolean     mCanSeekForward;
    private Context mContext;
    private String mFilePath;

    public FramePlayerView(Context context) {
        super(context);
        initVideoView();
    }

    public FramePlayerView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
        initVideoView();
    }

    public FramePlayerView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        initVideoView();
    }

    @Override
    public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(event);
        event.setClassName(FramePlayerView.class.getName());
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(FramePlayerView.class.getName());
    }

    private void initVideoView() {
        mContext = getContext();
        mVideoWidth = 0;
        mVideoHeight = 0;
        setSurfaceTextureListener(mSurfaceTextureListener);
        setFocusable(true);
        setFocusableInTouchMode(true);
        requestFocus();
        mCurrentState = STATE_IDLE;
        mTargetState  = STATE_IDLE;
    }

    public void setVideoPath(String path) {
        mFilePath = path;
        mSeekWhenPrepared = 0;
        openVideo();
        requestLayout();
        invalidate();
    }

    public void release() {
        try{
            if (mFramePlayer != null) {
                mFramePlayer.release();
            }
        }catch(Exception ex){
            Log.d(TAG, "Encounter exception when stop player: " + ex.getMessage());
        }
        mFramePlayer = null;
        mCurrentState = STATE_IDLE;
        mTargetState  = STATE_IDLE;
        mVideoRotation = 0;
        mVideoWidth = 0;
        mVideoHeight = 0;
    }

    private void openVideo() {
        if (mFilePath == null || mSurface == null) {
            // not ready for playback just yet, will try again later
            return;
        }
        // Tell the music playback service to pause
        // TODO: these constants need to be published somewhere in the framework.
        Intent i = new Intent("com.android.music.musicservicecommand");
        i.putExtra("command", "pause");
        mContext.sendBroadcast(i);

        // reset rotation and scale
        setRotation(0);
        setScaleX(1);
        setScaleY(1);

        // we shouldn't clear the target state, because somebody might have
        // called start() previously
        release(false);
        try {
            mFramePlayer = new FramePlayer();

            mFramePlayer.setOnPositionUpdateListener(mPositionUpdateListener);
            mFramePlayer.setOnVideoSizeChangedListener(mSizeChangedListener);
            mFramePlayer.setOnCompletionListener(mCompletionListener);
            mFramePlayer.setOnErrorListener(mErrorListener);
            mFramePlayer.setOnPreparedListener(mPreparedListener);

            mFramePlayer.setDataSource(mFilePath);
            mFramePlayer.setSurface(mSurface);
            mFramePlayer.prepareAsync();

            // we don't set the target state here either, but preserve the
            // target state that was there before.
            mCurrentState = STATE_PREPARING;
            attachMediaController();
        } catch (IOException ex) {
            Log.w(TAG, "Unable to open content: " + mFilePath, ex);
            mCurrentState = STATE_ERROR;
            mTargetState = STATE_ERROR;
            mErrorListener.onError(mFramePlayer, FramePlayer.FRAMEPLAYER_ERROR_UNKNOWN, 0);
        } catch (IllegalArgumentException ex) {
            Log.w(TAG, "Unable to open content: " + mFilePath, ex);
            mCurrentState = STATE_ERROR;
            mTargetState = STATE_ERROR;
            mErrorListener.onError(mFramePlayer, FramePlayer.FRAMEPLAYER_ERROR_UNKNOWN, 0);
        }
    }
    public void setMediaController(MediaController controller) {
        if (mMediaController != null) {
            mMediaController.hide();
        }
        mMediaController = controller;
        attachMediaController();
    }

    private void attachMediaController() {
        if (mFramePlayer != null && mMediaController != null) {
            mMediaController.setMediaPlayer(this);
            View anchorView = this.getParent() instanceof View ?
                    (View) this.getParent() : this;
            mMediaController.setAnchorView(anchorView);
            mMediaController.setEnabled(isInPlaybackState());
        }
    }

    @Override
    public void onMeasure(int widthMeasureSpec, int heightMeasureSpec){
        int width = getDefaultSize(mVideoWidth, widthMeasureSpec);
        int height = getDefaultSize(mVideoHeight, heightMeasureSpec);
        if(mVideoWidth != 0 && mVideoHeight != 0){
            int widthSpecMode = MeasureSpec.getMode(widthMeasureSpec);
            int widthSpecSize = MeasureSpec.getSize(widthMeasureSpec);
            int heightSpecMode = MeasureSpec.getMode(heightMeasureSpec);
            int heightSpecSize = MeasureSpec.getSize(heightMeasureSpec);

            if (widthSpecMode == MeasureSpec.EXACTLY && heightSpecMode == MeasureSpec.EXACTLY) {
                // the size is fixed
                height = heightSpecSize;
                width = widthSpecSize;

            } else if (widthSpecMode == MeasureSpec.EXACTLY) {
                // only the width is fixed, adjust the height to match aspect ratio if possible
                width = widthSpecSize;
                if(mVideoRotation == 90 || mVideoRotation == 270) {
                    height = width * mVideoWidth / mVideoHeight;
                }else {
                    height = width * mVideoHeight / mVideoWidth;
                }
//                if (heightSpecMode == MeasureSpec.AT_MOST && height > heightSpecSize) {
//                    // couldn't match aspect ratio within the constraints
//                    height = heightSpecSize;
//                }
            } else if (heightSpecMode == MeasureSpec.EXACTLY) {
                // only the height is fixed, adjust the width to match aspect ratio if possible
                height = heightSpecSize;
                if(mVideoRotation == 90 || mVideoRotation == 270){
                    width = height *  mVideoHeight/ mVideoWidth;
                }else{
                    width = height * mVideoWidth / mVideoHeight;
                }
//                if (widthSpecMode == MeasureSpec.AT_MOST && width > widthSpecSize) {
//                    // couldn't match aspect ratio within the constraints
//                    width = widthSpecSize;
//                }
            } else {
                // neither the width nor the height are fixed, try to use actual video size
                if(mVideoRotation == 90 || mVideoRotation == 270){
                    width = mVideoHeight;
                    height = mVideoWidth;
                    if (heightSpecMode == MeasureSpec.AT_MOST) {
                        // too tall, decrease both width and height
                        height = heightSpecSize;
                        width = height *  mVideoHeight/ mVideoWidth;
                    }
                    if (widthSpecMode == MeasureSpec.AT_MOST && width > widthSpecSize) {
                        // too wide, decrease both width and height
                        width = widthSpecSize;
                        height = width * mVideoWidth / mVideoHeight;
                    }
                }else{
                    width = mVideoWidth;
                    height = mVideoHeight;
                    if (heightSpecMode == MeasureSpec.AT_MOST) {
                        // too tall, decrease both width and height
                        height = heightSpecSize;
                        width = height * mVideoWidth / mVideoHeight;
                    }
                    if (widthSpecMode == MeasureSpec.AT_MOST && width > widthSpecSize) {
                        // too wide, decrease both width and height
                        width = widthSpecSize;
                        height = width * mVideoHeight / mVideoWidth;
                    }
                }

            }

            // adjust ratio
            if(mVideoRotation == 90 || mVideoRotation == 270){
                setScaleX((float)height/width);
                setScaleY((float)width/height);
            }
        }
        setMeasuredDimension(width, height);
    }

    FramePlayer.OnVideoSizeChangedListener mSizeChangedListener =
            new FramePlayer.OnVideoSizeChangedListener() {
                public void onVideoSizeChanged(final FramePlayer fp, final int width, final int height) {
                    post(new Runnable(){
                        @Override
                        public void run(){
                            if(mFramePlayer == null)
                                return;
                            mVideoWidth = width;
                            mVideoHeight = height;

                            if (mVideoWidth != 0 && mVideoHeight != 0) {
                                requestLayout();
                                if(mOnSizeChangeListener != null){
                                    mOnSizeChangeListener.onSizeChanged(getWidth(),getHeight());
                                }
                            }
                        }
                    });

                }
            };

    FramePlayer.OnPreparedListener mPreparedListener = new FramePlayer.OnPreparedListener() {
        public void onPrepared(final FramePlayer fp) {
            post(new Runnable() {
                @Override
                public void run() {
                    if(mFramePlayer == null)
                        return;
                    mCurrentState = STATE_PREPARED;
                    mCanPause = mCanSeekBack = mCanSeekForward = true;

                    if (mMediaController != null) {
                        mMediaController.setEnabled(true);
                    }
                    mVideoWidth = fp.getVideoWidth();
                    mVideoHeight = fp.getVideoHeight();
                    mVideoRotation = fp.getVideoRotation();

                    int seekToPosition = mSeekWhenPrepared;  // mSeekWhenPrepared may be changed after seekTo() call
                    if (seekToPosition != 0) {
                        seekTo(seekToPosition);
                    }
                    if (mVideoWidth != 0 && mVideoHeight != 0) {
                        setRotation(mVideoRotation);
                        requestLayout();
                        if (isValidSize()) {
                            // We didn't actually change the size (it was already at the size
                            // we need), so we won't get a "surface changed" callback, so
                            // start the video here instead of in the callback.
                            if (mTargetState == STATE_PLAYING) {
                                start();
                                if (mMediaController != null) {
                                    mMediaController.show();
                                }
                            } else if (!isPlaying() &&
                                    (seekToPosition != 0 || getCurrentPosition() > 0)) {
                                if (mMediaController != null) {
                                    // Show the media controls when we're paused into a video and make 'em stick.
                                    mMediaController.show(0);
                                }
                            }
                        }
                    } else {
                        // We don't know the video size yet, but should start anyway.
                        // The video size might be reported to us later.
                        if (mTargetState == STATE_PLAYING) {
                            start();
                        }
                    }

                    if (mOnPreparedListener != null) {
                        mOnPreparedListener.onPrepared(mFramePlayer);
                    }
                }
            });
        }
    };

    private FramePlayer.OnPositionUpdateListener mPositionUpdateListener = new FramePlayer.OnPositionUpdateListener() {
        @Override
        public void onPositionUpdate(final FramePlayer fp, final int currentPosition) {
            post(new Runnable() {
                @Override
                public void run() {
                    if(mFramePlayer == null)
                        return;
                    if(mOnPositionUpdateListener != null){
                        mOnPositionUpdateListener.onPositionUpdate(fp, currentPosition);
                    }
                }
            });
        }
    };
    private FramePlayer.OnCompletionListener mCompletionListener =
            new FramePlayer.OnCompletionListener() {
                public void onCompletion(FramePlayer fp) {
                    post(new Runnable() {
                        @Override
                        public void run() {
                            if(mFramePlayer == null)
                                return;
                            if (mCurrentState == STATE_PLAYBACK_COMPLETED) {
                                return;
                            }
                            mCurrentState = STATE_PLAYBACK_COMPLETED;
                            mTargetState = STATE_PLAYBACK_COMPLETED;
                            if (mMediaController != null) {
                                mMediaController.hide();
                            }
                            if (mOnCompletionListener != null) {
                                mOnCompletionListener.onCompletion(mFramePlayer);
                            }
                        }
                    });
                }
            };

    private FramePlayer.OnErrorListener mErrorListener =
            new FramePlayer.OnErrorListener() {
                public boolean onError(final FramePlayer mp, final int framework_err, final int impl_err) {
                    if(mFramePlayer == null)
                        return true;
                    final boolean[] wasHandled = new boolean[1];
                    wasHandled[0] = false;
                    post(new Runnable() {
                             @Override
                             public void run() {
                                 Log.d(TAG, "Error: " + framework_err + "," + impl_err);
                                 mCurrentState = STATE_ERROR;
                                 mTargetState = STATE_ERROR;
                                 if (mMediaController != null) {
                                     mMediaController.hide();
                                 }

                                 //If an error handler has been supplied, use it and finish.
                                 if (mOnErrorListener != null) {
                                     if (mOnErrorListener.onError(mFramePlayer, framework_err, impl_err)) {
                                         wasHandled[0] = true;
                                     }
                                 }

                                /* Otherwise, pop up an error dialog so the user knows that
                                 * something bad has happened. Only try and pop up the dialog
                                 * if we're attached to a window. When we're going away and no
                                 * longer have a window, don't bother showing the user an error.
                                 */
                                 if (getWindowToken() != null) {
                                     Resources r = mContext.getResources();
                                     int messageId;

                                     messageId = android.R.string.VideoView_error_text_unknown;

                                     new AlertDialog.Builder(mContext)
                                             .setMessage(messageId)
                                             .setPositiveButton(android.R.string.VideoView_error_button,
                                                     new DialogInterface.OnClickListener() {
                                                         public void onClick(DialogInterface dialog, int whichButton) {
                                        /* If we get here, there is no onError listener, so
                                         * at least inform them that the video is over.
                                         */
                                                             if (mOnCompletionListener != null) {
                                                                 mOnCompletionListener.onCompletion(mFramePlayer);
                                                             }
                                                         }
                                                     })
                                             .setCancelable(false)
                                             .show();
                                 }
                                 wasHandled[0] = true;
                             }
                         });

                    return wasHandled[0];
                }
            };

    /**
     * Register a callback to be invoked when the media file
     * is loaded and ready to go.
     *
     * @param l The callback that will be run
     */
    public void setOnPreparedListener(FramePlayer.OnPreparedListener l)
    {

        mOnPreparedListener = l;
    }

    /**
     * Register a callback to be invoked when the end of a media file
     * has been reached during playback.
     *
     * @param l The callback that will be run
     */
    public void setOnCompletionListener(FramePlayer.OnCompletionListener l)
    {
        mOnCompletionListener = l;
    }

    /**
     * Register a callback to be invoked when an error occurs
     * during playback or setup.  If no listener is specified,
     * or if the listener returned false, FramePlayerView will inform
     * the user of any errors.
     *
     * @param l The callback that will be run
     */
    public void setOnErrorListener(FramePlayer.OnErrorListener l)
    {
        mOnErrorListener = l;
    }

    /**
     * Interface definition of a callback to be invoked
     * when the size of the FramePlayerView has changed.
     */
    public interface OnSizeChangeListener
    {
        /**
         * Called to indicate an info or a warning.
         *
         * @param width     the width of the FramePlayerView.
         * @param height    the height of the FramePlayerView.
         */
        void onSizeChanged(int width, int height);
    }

    private OnSizeChangeListener mOnSizeChangeListener = null;

    public void setOnSizeChangeListener(OnSizeChangeListener listener) {
        mOnSizeChangeListener = listener;
    }

    private FramePlayer.OnSeekCompleteListener mOnSeekCompleteListener = null;
    public void setOnSeekCompleteListener(OnSizeChangeListener listener) {
        mOnSizeChangeListener = listener;
    }
    private FramePlayer.OnPositionUpdateListener mOnPositionUpdateListener = null;
    public void setOnPositionUpdateListener(FramePlayer.OnPositionUpdateListener listener) {
        mOnPositionUpdateListener = listener;
    }
    TextureView.SurfaceTextureListener mSurfaceTextureListener = new SurfaceTextureListener()
    {
        @Override
        public void onSurfaceTextureSizeChanged(final SurfaceTexture surface, final int width, final int height) {
            Log.i(TAG, "onSurfaceTextureSizeChanged " + width + "x" + height);
            mSurfaceWidth = width;
            mSurfaceHeight = height;
            boolean isValidState =  (mTargetState == STATE_PLAYING);
            boolean hasValidSize = isValidSize();
            if (mOnSizeChangeListener != null) {
                mOnSizeChangeListener.onSizeChanged(width, height);
            }
        }

        @Override
        public void onSurfaceTextureAvailable(final SurfaceTexture surface, final int width, final int height) {
            Log.i(TAG, "onSurfaceTextureAvailable " + width + "x" + height);
            mSurface = new Surface(surface);
            mSurfaceWidth = width;
            mSurfaceHeight = height;
            openVideo();
        }

        @Override
        public boolean onSurfaceTextureDestroyed(final SurfaceTexture surface) {
            // after we return from this we can't use the surface any more
            Log.i(TAG, "onSurfaceTextureDestroyed");
            mSurface = null;
            if (mMediaController != null) mMediaController.hide();
            release(true);
            return true;
        }
        @Override
        public void onSurfaceTextureUpdated(final SurfaceTexture surface) {
            // do nothing
        }
    };

    private boolean isValidSize() {
        if (mVideoWidth <= 0 || mVideoHeight <= 0 || mSurfaceWidth <= 0 || mSurfaceHeight <= 0) {
            return false;
        }

        int delta = mVideoWidth * mSurfaceHeight - mVideoHeight * mSurfaceWidth;
        if (delta < 0) {
            delta = -delta;
        }

        if (delta == 0) {
            return true;
        }

        return mVideoWidth * mSurfaceHeight / delta > 100;
    }

    /*
     * release the media player in any state
     */
    private void release(boolean cleartargetstate) {
        if (mFramePlayer != null) {
            mFramePlayer.release();
            mFramePlayer = null;
            mCurrentState = STATE_IDLE;
            if (cleartargetstate) {
                mTargetState  = STATE_IDLE;
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (isInPlaybackState() && mMediaController != null) {
            toggleMediaControlsVisiblity();
        }
        return false;
    }

    @Override
    public boolean onTrackballEvent(MotionEvent ev) {
        if (isInPlaybackState() && mMediaController != null) {
            toggleMediaControlsVisiblity();
        }
        return false;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event)
    {
        boolean isKeyCodeSupported = keyCode != KeyEvent.KEYCODE_BACK &&
                keyCode != KeyEvent.KEYCODE_VOLUME_UP &&
                keyCode != KeyEvent.KEYCODE_VOLUME_DOWN &&
                keyCode != KeyEvent.KEYCODE_VOLUME_MUTE &&
                keyCode != KeyEvent.KEYCODE_MENU &&
                keyCode != KeyEvent.KEYCODE_CALL &&
                keyCode != KeyEvent.KEYCODE_ENDCALL;
        if (isInPlaybackState() && isKeyCodeSupported && mMediaController != null) {
            if (keyCode == KeyEvent.KEYCODE_HEADSETHOOK ||
                    keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                if (mFramePlayer.isPlaying()) {
                    pause();
                    mMediaController.show();
                } else {
                    start();
                    mMediaController.hide();
                }
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) {
                if (!mFramePlayer.isPlaying()) {
                    start();
                    mMediaController.hide();
                }
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_MEDIA_STOP
                    || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) {
                if (mFramePlayer.isPlaying()) {
                    pause();
                    mMediaController.show();
                }
                return true;
            } else {
                toggleMediaControlsVisiblity();
            }
        }

        return super.onKeyDown(keyCode, event);
    }

    private void toggleMediaControlsVisiblity() {
        if (mMediaController.isShowing()) {
            mMediaController.hide();
        } else {
            mMediaController.show();
        }
    }

    @Override
    public void start() {
        if (isInPlaybackState()) {
            mFramePlayer.start();
            mCurrentState = STATE_PLAYING;
        }
        mTargetState = STATE_PLAYING;
    }

    @Override
    public void pause() {
        if (isInPlaybackState()) {
            if (mFramePlayer.isPlaying()) {
                mFramePlayer.pause();
                mCurrentState = STATE_PAUSED;
            }
        }
        mTargetState = STATE_PAUSED;
    }

    public void suspend() {
        release(false);
    }

    public void resume() {
        openVideo();
    }

    @Override
    public int getDuration() {
        if (isInPlaybackState()) {
            return (int)mFramePlayer.getDuration();
        }

        return -1;
    }
    public int getPerSampleDuration(){
        if (isInPlaybackState()) {
            return (int)mFramePlayer.getPerSampleDuration();
        }
        return -1;
    }
    public boolean isReachEOS(){
        if (isInPlaybackState()) {
            return mFramePlayer.isReachEOS();
        }
        return false;
    }

    @Override
    public int getCurrentPosition() {
        if (isInPlaybackState()) {
            return (int)mFramePlayer.getCurrentPosition();
        }
        return 0;
    }

    @Override
    public void seekTo(int msec) {
        if (isInPlaybackState()) {
            mFramePlayer.seekTo(msec);
            mSeekWhenPrepared = 0;
        } else {
            mSeekWhenPrepared = msec;
        }
    }
    public void seekToWithoutCallback(int msec) {
        if (isInPlaybackState()) {
            mFramePlayer.seekToWithoutCallback(msec);
            mSeekWhenPrepared = 0;
        } else {
            mSeekWhenPrepared = msec;
        }
    }
    @Override
    public boolean isPlaying() {
        return isInPlaybackState() && mFramePlayer.isPlaying();
    }

    @Override
    public int getBufferPercentage() {
        throw new RuntimeException("Not implemented");
    }

    @Override
    public boolean canPause() {
        return mCanPause;
    }

    @Override
    public boolean canSeekBackward() {
        return mCanSeekBack;
    }

    @Override
    public boolean canSeekForward() {
        return mCanSeekForward;
    }
    @Override
    public int getAudioSessionId() {
        throw new RuntimeException("Not implemented");
    }


    public boolean isInPlaybackState() {
        return (mFramePlayer != null &&
                mCurrentState != STATE_ERROR &&
                mCurrentState != STATE_IDLE &&
                mCurrentState != STATE_PREPARING);
    }

    public String getFilePath() {
        return mFilePath;
    }
}
