/// ============================================================================
///  Drive — the space's Google Drive backup status (mobile, read + light actions)
/// ============================================================================
///  Purpose:  model the connection status the mobile Spaces screen shows: which
///            Drive(s) back up this space, which is active, last sync, and how much
///            of each Drive's quota Trove is using. Connecting a new Drive and the
///            rotate/mirror mode stay web-only; mobile shows status, Sync now and
///            Disconnect.
/// ============================================================================
library;

class DriveConnection {
  const DriveConnection({
    required this.id,
    required this.googleEmail,
    required this.active,
    this.googleAccountName,
    this.connectedByName,
    this.status,
    this.connectedAt,
    this.lastSyncAt,
    this.storageLimitBytes,
    this.storageUsageBytes,
    this.troveBytes,
  });

  final String id;
  final String googleEmail;
  final bool active;
  final String? googleAccountName;
  final String? connectedByName;
  final String? status;
  final DateTime? connectedAt;
  final DateTime? lastSyncAt;
  final num? storageLimitBytes;
  final num? storageUsageBytes;
  final num? troveBytes;

  factory DriveConnection.fromJson(Map<String, dynamic> j) {
    DateTime? d(Object? v) => v == null ? null : DateTime.tryParse(v.toString());
    num? n(Object? v) => v is num ? v : null;
    return DriveConnection(
      id: j['id']?.toString() ?? '',
      googleEmail: j['googleEmail']?.toString() ?? '',
      active: j['active'] == true,
      googleAccountName: j['googleAccountName']?.toString(),
      connectedByName: j['connectedByName']?.toString(),
      status: j['status']?.toString(),
      connectedAt: d(j['connectedAt']),
      lastSyncAt: d(j['lastSyncAt']),
      storageLimitBytes: n(j['storageLimitBytes']),
      storageUsageBytes: n(j['storageUsageBytes']),
      troveBytes: n(j['troveBytes']),
    );
  }
}

class DriveStatus {
  const DriveStatus({required this.connected, required this.mode, required this.connections});

  final bool connected;
  final String mode;
  final List<DriveConnection> connections;

  factory DriveStatus.fromJson(Map<String, dynamic> j) {
    final list = (j['connections'] as List?) ?? const [];
    return DriveStatus(
      connected: j['connected'] == true,
      mode: j['mode']?.toString() ?? 'rotate',
      connections: list
          .map((e) => DriveConnection.fromJson((e as Map).cast<String, dynamic>()))
          .toList(),
    );
  }
}

/// The result of a manual "Sync now".
class DriveSyncResult {
  const DriveSyncResult({required this.synced, required this.skipped});
  final int synced;
  final int skipped;
}
