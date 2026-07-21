/// ============================================================================
///  ApiClient — the notice-aware HTTP core (D23)
/// ============================================================================
///
///  Purpose
///  -------
///  One Dio wrapper that every feature uses. It attaches the JWT, measures each
///  call's round-trip, reads the server's `X-Trove-Request-Id`, records a DevLogEntry
///  for the Developer drawer, and — on failure — parses the server's two-channel
///  Notice (or synthesizes one for offline/timeout) and pushes it to the NoticeCenter.
///
///  Business use case
///  -----------------
///  This is where "the app is legible" is enforced: no request is silent. Success is
///  logged for the drawer; failure surfaces a calm toast with the developer note one
///  tap away. Built first, on purpose, so every feature inherits it (D23).
///
///  Solution architecture
///  ---------------------
///  Dio interceptors: onRequest stamps a stopwatch + auth header; onResponse logs a
///  success entry; onError logs a failure entry, builds an ApiException carrying a
///  Notice, and (unless the caller opts out) shows a toast. Extraction meta on a
///  document response is folded into the log entry so the chain trail is visible.
///
///  Reasoning & logic
///  -----------------
///  The token is fetched lazily via an injected getter to avoid a dependency cycle
///  with the auth layer. Nothing secret is logged — the Authorization header is never
///  copied into the DevLogEntry.
/// ============================================================================
library;

import 'package:dio/dio.dart';

import '../config.dart';
import '../notice/dev_log.dart';
import '../notice/notice.dart';
import '../notice/notice_center.dart';
import 'api_exception.dart';

typedef TokenGetter = Future<String?> Function();

/// Marks a request so the interceptor won't auto-show a toast (the caller will handle
/// the notice itself — e.g. inline form errors). Read from RequestOptions.extra.
const String kSilentNotice = 'silentNotice';

class ApiClient {
  ApiClient({required TokenGetter tokenGetter, Dio? dio})
      : _tokenGetter = tokenGetter,
        _dio = dio ?? Dio() {
    _dio.options
      ..baseUrl = AppConfig.apiBase
      ..connectTimeout = const Duration(seconds: 10)
      ..receiveTimeout = const Duration(seconds: 90) // vision extraction can be slow
      ..sendTimeout = const Duration(seconds: 60);
    _dio.interceptors.add(InterceptorsWrapper(
      onRequest: _onRequest,
      onResponse: _onResponse,
      onError: _onError,
    ),);
  }

  final Dio _dio;
  final TokenGetter _tokenGetter;

  Dio get raw => _dio;

  // ---- interceptors -------------------------------------------------------

  Future<void> _onRequest(
      RequestOptions options, RequestInterceptorHandler handler,) async {
    options.extra['sw'] = Stopwatch()..start();
    final token = await _tokenGetter();
    if (token != null && token.isNotEmpty) {
      options.headers['Authorization'] = 'Bearer $token';
    }
    handler.next(options);
  }

  void _onResponse(Response<dynamic> response, ResponseInterceptorHandler handler) {
    DeveloperLog.instance.add(_entry(
      response.requestOptions,
      response.statusCode ?? 200,
      response.headers.value('x-trove-request-id'),
      extractionMeta: _extractionMetaOf(response.data),
    ),);
    handler.next(response);
  }

  void _onError(DioException err, ErrorInterceptorHandler handler) {
    final status = err.response?.statusCode ?? 0;
    final requestId = err.response?.headers.value('x-trove-request-id');
    final notice = _noticeFromError(err, status);
    DeveloperLog.instance.add(_entry(
      err.requestOptions, status, requestId, notice: notice,
    ),);
    final silent = err.requestOptions.extra[kSilentNotice] == true;
    // 401 is handled centrally by the auth layer (redirect to login); still toast it.
    if (!silent) {
      NoticeCenter.instance.show(notice);
    }
    handler.reject(
      err.copyWith(error: ApiException(
        statusCode: status, notice: notice, requestId: requestId,
      ),),
    );
  }

  // ---- helpers ------------------------------------------------------------

  DevLogEntry _entry(RequestOptions o, int status, String? requestId,
      {Notice? notice, Map<String, dynamic>? extractionMeta,}) {
    final sw = o.extra['sw'];
    final ms = sw is Stopwatch ? sw.elapsedMilliseconds : 0;
    return DevLogEntry(
      at: DateTime.now(),
      method: o.method,
      path: o.path,
      statusCode: status,
      durationMs: ms,
      requestId: requestId,
      notice: notice,
      extractionMeta: extractionMeta,
    );
  }

  Notice _noticeFromError(DioException err, int status) {
    final data = err.response?.data;
    if (data is Map && data['notice'] is Map) {
      return Notice.fromJson((data['notice'] as Map).cast<String, dynamic>());
    }
    if (data is Map && data['message'] is String) {
      return Notice.local(
        level: NoticeLevel.error,
        code: 'HTTP_$status',
        userMessage: data['message'] as String,
        devNote: 'HTTP $status (no notice envelope).',
      );
    }
    // Never reached the server, or a non-JSON failure.
    switch (err.type) {
      case DioExceptionType.connectionTimeout:
      case DioExceptionType.receiveTimeout:
      case DioExceptionType.sendTimeout:
        return Notice.local(
          level: NoticeLevel.warning,
          code: 'TIMEOUT',
          userMessage: 'That took too long — check your connection and try again.',
          devNote: 'Dio ${err.type.name} against ${err.requestOptions.uri}.',
        );
      case DioExceptionType.connectionError:
        return Notice.local(
          level: NoticeLevel.warning,
          code: 'OFFLINE',
          userMessage: "Can't reach Trove right now — you appear to be offline.",
          devNote: 'Connection error to ${AppConfig.apiBase}.',
        );
      default:
        return Notice.local(
          level: NoticeLevel.error,
          code: status == 0 ? 'NETWORK' : 'HTTP_$status',
          userMessage: 'Something went wrong. Please try again.',
          devNote: 'Dio ${err.type.name}; status $status.',
        );
    }
  }

  /// Pulls `extra.extractionMeta` out of a document response for the drawer, if present.
  Map<String, dynamic>? _extractionMetaOf(dynamic data) {
    if (data is Map && data['extra'] is Map) {
      final extra = (data['extra'] as Map).cast<String, dynamic>();
      final meta = extra['extractionMeta'];
      if (meta is Map) return meta.cast<String, dynamic>();
    }
    return null;
  }

  // ---- verbs --------------------------------------------------------------

  Future<dynamic> get(String path, {Map<String, dynamic>? query, bool silent = false}) async {
    final r = await _dio.get<dynamic>(path,
        queryParameters: query, options: Options(extra: {kSilentNotice: silent}),);
    return r.data;
  }

  /// Fetches raw bytes (e.g. a vital document's decrypted content stream).
  Future<List<int>> getBytes(String path, {bool silent = false}) async {
    final r = await _dio.get<List<int>>(path,
        options: Options(
          responseType: ResponseType.bytes,
          extra: {kSilentNotice: silent},
        ),);
    return r.data ?? const <int>[];
  }

  Future<dynamic> post(String path,
      {Object? body, Map<String, dynamic>? query, bool silent = false,}) async {
    final r = await _dio.post<dynamic>(path,
        data: body,
        queryParameters: query,
        options: Options(extra: {kSilentNotice: silent}),);
    return r.data;
  }

  Future<dynamic> postMultipart(String path, FormData form,
      {Map<String, dynamic>? query, bool silent = false,}) async {
    final r = await _dio.post<dynamic>(path,
        data: form,
        queryParameters: query,
        options: Options(extra: {kSilentNotice: silent}),);
    return r.data;
  }
}
