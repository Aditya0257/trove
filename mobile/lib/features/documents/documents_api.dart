/// ============================================================================
///  documents_api — upload / get / confirm / list documents + categories
/// ============================================================================
///
///  Purpose
///  -------
///  The document use-cases over the notice-aware ApiClient. Upload sends the image as
///  multipart; confirm applies reviewer edits; list/get read back. Categories back the
///  confirm-screen dropdown.
///
///  Design
///  ------
///  A thin injectable class (no state) plus a few Riverpod providers. Errors bubble as
///  ApiException (already toasted by the client) so screens just await + react.
/// ============================================================================
library;

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/models/category.dart';
import '../../core/models/document.dart';
import '../../core/providers.dart';

class DocumentsApi {
  DocumentsApi(this._api);
  final ApiClient _api;

  /// Uploads an image file to a space; returns the created (needs_review) document.
  /// `extract` false skips AI reading (used by Mail, whose fields are user-set) so
  /// the async extractor never overwrites the document's category/extra after a confirm.
  Future<TroveDocument> upload({
    required String spaceId,
    required String filePath,
    bool vital = false,
    bool extract = true,
  }) async {
    final form = FormData.fromMap({
      'file': await MultipartFile.fromFile(filePath),
    });
    final data = await _api.postMultipart(
      '/api/documents',
      form,
      query: {'spaceId': spaceId, 'vital': vital, 'extract': extract},
    ) as Map<String, dynamic>;
    return TroveDocument.fromJson(data);
  }

  Future<TroveDocument> get(String id) async {
    final data = await _api.get('/api/documents/$id') as Map<String, dynamic>;
    return TroveDocument.fromJson(data);
  }

  /// The raw file bytes (decrypted for vital documents) - used to preview or open a PDF.
  Future<List<int>> contentBytes(String id) {
    return _api.getBytes('/api/documents/$id/content');
  }

  /// One page of live documents. size 0 = all (back-compatible); pass a size to page
  /// through a large vault. The server returns the page as the array body.
  Future<List<TroveDocument>> list({
    required String spaceId,
    String? category,
    int page = 0,
    int size = 0,
  }) async {
    final data = await _api.get('/api/documents', query: {
      'spaceId': spaceId,
      if (category != null) 'category': category,
      if (size > 0) 'page': page,
      if (size > 0) 'size': size,
    },) as List<dynamic>;
    return data
        .map((e) => TroveDocument.fromJson((e as Map).cast<String, dynamic>()))
        .toList();
  }

  /// Trashed documents in a space (recoverable for 30 days).
  Future<List<TroveDocument>> trash(String spaceId) async {
    final data = await _api.get('/api/documents/trash', query: {'spaceId': spaceId})
        as List<dynamic>;
    return data
        .map((e) => TroveDocument.fromJson((e as Map).cast<String, dynamic>()))
        .toList();
  }

  /// Move a document to Trash (soft delete, recoverable for 30 days).
  Future<void> delete(String id) async {
    await _api.delete('/api/documents/$id');
  }

  /// Restore a trashed document back to the live vault.
  Future<void> restore(String id) async {
    await _api.post('/api/documents/$id/restore');
  }

  /// Permanently delete a trashed document (no undo).
  Future<void> purge(String id) async {
    await _api.delete('/api/documents/$id/purge');
  }

  /// Confirms a review with any reviewer edits. Only non-null fields are sent.
  Future<TroveDocument> confirm(
    String id, {
    String? category,
    String? merchant,
    String? docDate,
    double? amount,
    String? currency,
    String? dueDate,
    bool? vital,
    Map<String, dynamic>? extra,
  }) async {
    final body = <String, dynamic>{
      if (category != null) 'category': category,
      if (merchant != null) 'merchant': merchant,
      if (docDate != null) 'docDate': docDate,
      if (amount != null) 'amount': amount,
      if (currency != null) 'currency': currency,
      if (dueDate != null) 'dueDate': dueDate,
      if (vital != null) 'vital': vital,
      // Confirm replaces `extra` wholesale on the backend, so callers pass the full map
      // (existing keys + any edits) to avoid wiping the extraction trail / anomaly.
      if (extra != null) 'extra': extra,
    };
    final data = await _api.post('/api/documents/$id/confirm', body: body)
        as Map<String, dynamic>;
    return TroveDocument.fromJson(data);
  }
}

final documentsApiProvider =
    Provider<DocumentsApi>((ref) => DocumentsApi(ref.watch(apiClientProvider)));

/// A counter bumped whenever a document is confirmed (or otherwise changed). An open
/// document list listens to it and reloads, so returning from the confirm screen shows
/// the newly added document without a manual pull-to-refresh.
final documentsChangedProvider = StateProvider<int>((_) => 0);

/// Global + space categories for the confirm dropdown.
final categoriesProvider = FutureProvider.autoDispose<List<Category>>((ref) async {
  final api = ref.watch(apiClientProvider);
  final data = await api.get('/api/categories') as List<dynamic>;
  return data
      .map((e) => Category.fromJson((e as Map).cast<String, dynamic>()))
      .toList();
});

/// Documents in a space (optionally filtered by category), for the list screen.
final documentsProvider = FutureProvider.autoDispose
    .family<List<TroveDocument>, ({String spaceId, String? category})>((ref, arg) {
  return ref.watch(documentsApiProvider).list(spaceId: arg.spaceId, category: arg.category);
});
