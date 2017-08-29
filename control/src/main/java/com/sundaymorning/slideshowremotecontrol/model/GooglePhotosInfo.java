package com.sundaymorning.slideshowremotecontrol.model;

public class GooglePhotosInfo {

    public boolean googlePhotosEnabled;
    public String googleToken;
    public String googleAccount;
    public int googlePhotoAlbumIndex;

    public GooglePhotosInfo() {
    }

    public GooglePhotosInfo(boolean googlePhotosEnabled, String googleToken, String googleAccount, int googlePhotoAlbumIndex) {
        this.googlePhotosEnabled = googlePhotosEnabled;
        this.googleToken = googleToken;
        this.googleAccount = googleAccount;
        this.googlePhotoAlbumIndex = googlePhotoAlbumIndex;
    }
}
