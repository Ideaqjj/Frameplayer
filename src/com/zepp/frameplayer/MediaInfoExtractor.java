package com.zepp.frameplayer;

import com.googlecode.mp4parser.authoring.Movie;
import com.googlecode.mp4parser.authoring.Track;
import com.googlecode.mp4parser.authoring.TrackMetaData;
import com.googlecode.mp4parser.authoring.container.mp4.MovieCreator;
import com.googlecode.mp4parser.util.Matrix;

import java.io.IOException;
import java.lang.reflect.Field;

public class MediaInfoExtractor {
    private static final String MP4_VIDEO_TAG = "vide";
    private static final MatrixReader sMatrixReader = new MatrixReader();
    private static final Object EXTRACT_LOCKER = new Object();
    // disable public construction.
    private MediaInfoExtractor(){}

    public static class MediaInfo{
        public int rotation;
        public long fps = 0;
        public long durationUs;
        public long totalFrames;
        public long perFrameDurationUs;
        public double width;
        public double height;
        public long[] keyFrameIndexes;
    }

    public static MediaInfo extract(String filePath) throws IOException, IllegalArgumentException {
        MediaInfo mediaInfo = new MediaInfo();
        Movie video = null;

        // It seems MovieCreator.build method is not thread safe. it will throw exception when
        // multiple threads call this function concurrently.So we use synchronized block here for workaround.
        synchronized (EXTRACT_LOCKER){
            video = MovieCreator.build(filePath);
        }

        assert(video.getTracks().size() > 0);
        for(int i=0; i<video.getTracks().size();i++){
            Track videoTrack = video.getTracks().get(i);
            if(videoTrack.getHandler().equals(MP4_VIDEO_TAG)){
                TrackMetaData metaData = videoTrack.getTrackMetaData();
                long timeScale = metaData.getTimescale();
                mediaInfo.width = metaData.getWidth();
                mediaInfo.height = metaData.getHeight();

                mediaInfo.rotation = convertMatrixToRotation(metaData.getMatrix());
                mediaInfo.durationUs = videoTrack.getDuration() * 1000 *1000 / timeScale;
                mediaInfo.totalFrames = videoTrack.getSamples().size();

                // calculate the fps
                mediaInfo.perFrameDurationUs =  mediaInfo.durationUs /  mediaInfo.totalFrames;
                mediaInfo.fps = 1*1000*1000 / mediaInfo.perFrameDurationUs;

                // get information of key frames
                mediaInfo.keyFrameIndexes = videoTrack.getSyncSamples().clone();
                return mediaInfo;
            }
        }
        throw new IllegalArgumentException("Cannot find video track in target file.");
    }
    private static int convertMatrixToRotation(Matrix matrix) throws IllegalArgumentException{
        if(rotationEquals(matrix, Matrix.ROTATE_0)){
            return 0;
        }else if(rotationEquals(matrix, Matrix.ROTATE_90)){
            return 90;
        }else if(rotationEquals(matrix, Matrix.ROTATE_180)){
            return 180;
        }else if(rotationEquals(matrix, Matrix.ROTATE_270)){
            return 270;
        }else{
            return 0;
//            throw new IllegalArgumentException("Rotation information is invalid.");
        }
    }
    private static boolean rotationEquals(Matrix source, Matrix target){
        try{
            if (Double.compare(sMatrixReader.getA(source), sMatrixReader.getA(target)) != 0) return false;
            if (Double.compare(sMatrixReader.getB(source), sMatrixReader.getB(target)) != 0) return false;
            if (Double.compare(sMatrixReader.getC(source), sMatrixReader.getC(target)) != 0) return false;
            if (Double.compare(sMatrixReader.getD(source), sMatrixReader.getD(target)) != 0) return false;
            return true;
        }   catch(Exception ex){return false;}
    }

    private static class MatrixReader{
        public static final String FIELD_NAME_A = "a";
        public static final String FIELD_NAME_B = "b";
        public static final String FIELD_NAME_C = "c";
        public static final String FIELD_NAME_D = "d";

        private Field mField_A;
        private Field mField_B;
        private Field mField_C;
        private Field mField_D;

        public MatrixReader(){
            try{
                mField_A = Matrix.class.getDeclaredField(FIELD_NAME_A);
                mField_A.setAccessible(true);
                mField_B = Matrix.class.getDeclaredField(FIELD_NAME_B);
                mField_B.setAccessible(true);
                mField_C = Matrix.class.getDeclaredField(FIELD_NAME_C);
                mField_C.setAccessible(true);
                mField_D = Matrix.class.getDeclaredField(FIELD_NAME_D);
                mField_D.setAccessible(true);
            }catch (NoSuchFieldException ex){
                // safe ignore
            }
        }
        public double getA(Matrix m) throws IllegalAccessException{
            return  ((Double)mField_A.get(m)).doubleValue();
        }
        public double getB(Matrix m) throws IllegalAccessException{
            return  ((Double)mField_B.get(m)).doubleValue();
        }
        public double getC(Matrix m) throws IllegalAccessException{
            return  ((Double)mField_C.get(m)).doubleValue();
        }
        public double getD(Matrix m) throws IllegalAccessException{
            return  ((Double)mField_D.get(m)).doubleValue();
        }
    }

}
