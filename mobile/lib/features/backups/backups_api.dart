/// ============================================================================
///  backups_api - data-health + backup calls (/api/integrity, /api/integrations)
/// ============================================================================
///
///  Purpose
///  -------
///  Read-only visibility into Trove's "the data is not disposable" promise: how many
///  copies of each document exist (primary R2, sidecars, mirror, Drive), a history of
///  backup runs, and Google Drive connection status - plus a single write, "sync now",
///  that kicks the Drive mirror. Everything speaks to interfaces on the backend, so the
///  client just reports what the health endpoints say.
/// ============================================================================
library;

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/providers.dart';

/// One recorded backup attempt (nightly dump, mirror copy, Drive sync, ...).
class BackupRun {
  const BackupRun({
    required this.kind,
    required this.status,
    this.location,
    this.detail,
    this.startedAt,
    this.finishedAt,
  });

  final String kind;
  final String status;
  final String? location;
  final String? detail;
  final String? startedAt;
  final String? finishedAt;

  factory BackupRun.fromJson(Map<String, dynamic> json) => BackupRun(
        kind: (json['kind'] as String?) ?? 'unknown',
        status: (json['status'] as String?) ?? 'unknown',
        location: json['location'] as String?,
        detail: json['detail'] as String?,
        startedAt: json['startedAt'] as String?,
        finishedAt: json['finishedAt'] as String?,
      );
}

/// Whether the Google Drive (Tier 3, human-navigable) mirror is wired up.
class DriveStatus {
  const DriveStatus({
    required this.connected,
    required this.mode,
    required this.connectionCount,
  });

  final bool connected;
  final String mode;
  final int connectionCount;

  factory DriveStatus.fromJson(Map<String, dynamic> json) => DriveStatus(
        connected: (json['connected'] as bool?) ?? false,
        mode: (json['mode'] as String?) ?? 'unknown',
        connectionCount: (json['connections'] as List?)?.length ?? 0,
      );
}

/// A count of how many documents have a healthy copy in each independent tier.
class IntegrityReport {
  const IntegrityReport({
    required this.documents,
    required this.primaryOk,
    required this.sidecarOk,
    required this.mirrorOk,
    required this.driveOk,
    required this.criticalCount,
    this.checkedAt,
  });

  final int documents;
  final int primaryOk;
  final int sidecarOk;
  final int? mirrorOk;
  final int driveOk;
  final int criticalCount;
  final String? checkedAt;

  factory IntegrityReport.fromJson(Map<String, dynamic> json) => IntegrityReport(
        documents: (json['documents'] as num?)?.toInt() ?? 0,
        primaryOk: (json['primaryOk'] as num?)?.toInt() ?? 0,
        sidecarOk: (json['sidecarOk'] as num?)?.toInt() ?? 0,
        mirrorOk: (json['mirrorOk'] as num?)?.toInt(),
        driveOk: (json['driveOk'] as num?)?.toInt() ?? 0,
        criticalCount: (json['criticalCount'] as num?)?.toInt() ?? 0,
        checkedAt: json['checkedAt'] as String?,
      );
}

class BackupsApi {
  BackupsApi(this._api);
  final ApiClient _api;

  Future<IntegrityReport> integrity(String spaceId) async {
    final data = await _api.get('/api/integrity/report',
        query: {'spaceId': spaceId},) as Map<String, dynamic>;
    return IntegrityReport.fromJson(data);
  }

  Future<List<BackupRun>> history() async {
    final data = await _api.get('/api/integrity/history') as List;
    return data
        .map((e) => BackupRun.fromJson((e as Map).cast<String, dynamic>()))
        .toList();
  }

  Future<DriveStatus> driveStatus(String spaceId) async {
    final data = await _api.get('/api/integrations/google-drive/status',
        query: {'spaceId': spaceId},) as Map<String, dynamic>;
    return DriveStatus.fromJson(data);
  }

  Future<void> driveSync(String spaceId) async {
    await _api.post('/api/integrations/google-drive/sync',
        query: {'spaceId': spaceId},);
  }
}

final backupsApiProvider = Provider<BackupsApi>(
  (ref) => BackupsApi(ref.watch(apiClientProvider)),
);
