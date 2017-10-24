package org.wordpress.android.ui.media;

public enum MediaBrowserType {
    BROWSER,                 // browse & manage media
    EDITOR_PICKER,           // select multiple images or videos to insert into a post
    SINGLE_IMAGE_PICKER;     // select a single image

    public boolean isPicker() {
        return this == EDITOR_PICKER || this == SINGLE_IMAGE_PICKER;
    }

    /*
     * multiselect is only availble when inserting into the editor
     */
    public boolean canMultiselect() {
        return this == EDITOR_PICKER;
    }

    public boolean imagesOnly() {
        return this == SINGLE_IMAGE_PICKER;
    }

    public boolean deviceOnly() {
        return this == SINGLE_IMAGE_PICKER;
    }
}
