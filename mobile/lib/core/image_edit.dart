/// ============================================================================
///  imageEdit - an in-app "adjust photo" step (rotate + crop) before upload
/// ============================================================================
///
///  Purpose
///  -------
///  Photos of bills, letters and email screens are often skewed, upside-down, or have
///  a lot of desk around the edges. This opens a crop + rotate editor so the user can
///  straighten and trim before the image is uploaded and read.
///
///  Design
///  ------
///  A normal Flutter screen (Scaffold + AppBar + SafeArea), NOT a native crop activity.
///  That is deliberate: the native editor (uCrop) runs as its own Android activity and,
///  on Android 15/16, the OS forces it edge-to-edge and ignores the opt-out, so its
///  toolbar drew under the status bar (the clock/battery overlapped the done button).
///  An in-app screen respects the app's own insets, so the controls always sit below the
///  status bar. Cropping is pure Dart (crop_your_image); rotation re-encodes the bytes
///  with the `image` package on a background isolate so the UI never janks. Cancelling
///  returns the original path, so the flow never dead-ends.
/// ============================================================================
library;

import 'dart:io';

import 'package:crop_your_image/crop_your_image.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:image/image.dart' as img;
import 'package:path_provider/path_provider.dart';

/// Opens the crop + rotate editor on [sourcePath]; returns the edited image's path, or
/// the original path if the user backs out. [title] labels the editor's app bar.
Future<String> cropImage(BuildContext context, String sourcePath,
    {String title = 'Adjust photo',}) async {
  final bytes = await File(sourcePath).readAsBytes();
  if (!context.mounted) return sourcePath;
  final edited = await Navigator.of(context).push<Uint8List>(
    MaterialPageRoute(builder: (_) => _CropScreen(bytes: bytes, title: title)),
  );
  if (edited == null) return sourcePath; // cancelled - keep the original
  final dir = await getTemporaryDirectory();
  final path = '${dir.path}/trove_edit_${DateTime.now().microsecondsSinceEpoch}.jpg';
  await File(path).writeAsBytes(edited, flush: true);
  return path;
}

/// Rotates encoded image bytes 90 degrees clockwise, re-encoded as JPEG. Runs on a
/// background isolate (via compute) so a large photo doesn't hitch the UI.
Uint8List _rotate90(Uint8List bytes) {
  final decoded = img.decodeImage(bytes);
  if (decoded == null) return bytes;
  return img.encodeJpg(img.copyRotate(decoded, angle: 90), quality: 90);
}

class _CropScreen extends StatefulWidget {
  const _CropScreen({required this.bytes, required this.title});

  final Uint8List bytes;
  final String title;

  @override
  State<_CropScreen> createState() => _CropScreenState();
}

class _CropScreenState extends State<_CropScreen> {
  final _controller = CropController();
  late Uint8List _image = widget.bytes;
  // Bumped on rotate so the Crop widget re-reads the new bytes and resets its rect.
  int _rev = 0;
  bool _busy = false;

  Future<void> _rotate() async {
    if (_busy) return;
    setState(() => _busy = true);
    final rotated = await compute(_rotate90, _image);
    if (!mounted) return;
    setState(() {
      _image = rotated;
      _rev++;
      _busy = false;
    });
  }

  void _done() {
    if (_busy) return;
    setState(() => _busy = true);
    _controller.crop(); // result arrives in onCropped
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        backgroundColor: Colors.black,
        foregroundColor: Colors.white,
        title: Text(widget.title),
        leading: IconButton(
          icon: const Icon(Icons.close),
          tooltip: 'Cancel',
          onPressed: () => Navigator.of(context).pop(),
        ),
        actions: [
          IconButton(
            icon: const Icon(Icons.check),
            tooltip: 'Done',
            onPressed: _busy ? null : _done,
          ),
        ],
      ),
      body: SafeArea(
        child: Column(
          children: [
            Expanded(
              child: Crop(
                key: ValueKey(_rev),
                image: _image,
                controller: _controller,
                interactive: true,
                baseColor: Colors.black,
                maskColor: Colors.black.withValues(alpha: 0.55),
                onCropped: (result) {
                  if (result is CropSuccess) {
                    Navigator.of(context).pop(result.croppedImage);
                  } else {
                    // Couldn't crop: let the user try again rather than dead-ending.
                    if (mounted) setState(() => _busy = false);
                  }
                },
              ),
            ),
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 10),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  TextButton.icon(
                    onPressed: _busy ? null : _rotate,
                    icon: const Icon(Icons.rotate_right, color: Colors.white),
                    label: const Text('Rotate', style: TextStyle(color: Colors.white)),
                  ),
                  const Text(
                    'Drag the corners to crop. Pinch to zoom.',
                    style: TextStyle(color: Colors.white54, fontSize: 12),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
