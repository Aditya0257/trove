/// ============================================================================
///  imageEdit - a small "adjust photo" step (rotate + crop) before upload
/// ============================================================================
///
///  Purpose
///  -------
///  Photos of bills, letters and email screens are often skewed, upside-down, or
///  have a lot of desk around the edges. This opens the native crop + rotate editor
///  (uCrop on Android, TOCropViewController on iOS via image_cropper) so the user can
///  straighten and trim before the image is uploaded and read.
///
///  Design
///  ------
///  One free-form call: no forced aspect ratio, so a receipt or a full page both work.
///  A coloured toolbar + status bar are set explicitly so the editor's title and its
///  cancel / done buttons render in a proper app bar BELOW the system status bar,
///  rather than edge-to-edge under the notification icons. Returns the edited image's
///  path, or the original path if the user cancels - so the caller always has a usable
///  file and the flow never dead-ends.
/// ============================================================================
library;

import 'package:flutter/material.dart';
import 'package:image_cropper/image_cropper.dart';

// The app's vault-teal, reused for the editor chrome so it feels part of Trove.
const Color _toolbar = Color(0xFF2F6F6A);

/// Opens the crop + rotate editor on [sourcePath]; returns the edited path (or the
/// original if the user backs out). [title] labels the editor's toolbar.
Future<String> cropImage(String sourcePath, {String title = 'Adjust photo'}) async {
  final cropped = await ImageCropper().cropImage(
    sourcePath: sourcePath,
    compressQuality: 90,
    uiSettings: [
      AndroidUiSettings(
        toolbarTitle: title,
        toolbarColor: _toolbar,
        toolbarWidgetColor: Colors.white,
        backgroundColor: Colors.black,
        activeControlsWidgetColor: _toolbar,
        hideBottomControls: false,
        lockAspectRatio: false,
      ),
      IOSUiSettings(title: title, aspectRatioLockEnabled: false),
    ],
  );
  return cropped?.path ?? sourcePath;
}
