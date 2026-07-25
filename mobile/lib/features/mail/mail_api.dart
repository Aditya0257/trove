/// ============================================================================
///  mail_api - filed email threads (/api/mail, /api/documents/mail-bundle)
/// ============================================================================
///
///  Purpose
///  -------
///  Read-side access to emails that were forwarded in and auto-filed. An email
///  becomes a "bundle": one thread grouped by account/address/topic, whose pages
///  are stored as ordinary documents (each screenshot is a TroveDocument whose
///  image lives at `fileUrl`). This exposes a paged list of bundles and the docs
///  that make up one thread.
/// ============================================================================
library;

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/models/document.dart';
import '../../core/providers.dart';

/// One filed email thread: its grouping metadata plus the screenshots it holds.
class MailBundle {
  const MailBundle({
    required this.bundleId,
    required this.account,
    required this.address,
    required this.topic,
    required this.subject,
    required this.date,
    required this.count,
    required this.docs,
  });

  final String bundleId;
  final String account;
  final String address;
  final String topic;
  final String subject;
  final String date;
  final int count;
  final List<TroveDocument> docs;

  factory MailBundle.fromJson(Map<String, dynamic> json) => MailBundle(
        bundleId: (json['bundleId'] as String?) ?? '',
        account: (json['account'] as String?) ?? '',
        address: (json['address'] as String?) ?? '',
        topic: (json['topic'] as String?) ?? '',
        subject: (json['subject'] as String?) ?? '',
        date: (json['date'] as String?) ?? '',
        count: (json['count'] as num?)?.toInt() ?? 0,
        docs: ((json['docs'] as List?) ?? const [])
            .map((e) => TroveDocument.fromJson((e as Map).cast<String, dynamic>()))
            .toList(),
      );
}

/// A page of bundles plus the facets a caller can use to filter later.
class MailPage {
  const MailPage({
    required this.bundles,
    required this.total,
    required this.accounts,
    required this.topics,
    required this.addresses,
  });

  final List<MailBundle> bundles;
  final int total;
  final List<String> accounts;
  final List<String> topics;
  final List<String> addresses;

  static List<String> _strings(dynamic v) =>
      ((v as List?) ?? const []).map((e) => e.toString()).toList();

  factory MailPage.fromJson(Map<String, dynamic> json) => MailPage(
        bundles: ((json['bundles'] as List?) ?? const [])
            .map((e) => MailBundle.fromJson((e as Map).cast<String, dynamic>()))
            .toList(),
        total: (json['total'] as num?)?.toInt() ?? 0,
        accounts: _strings(json['accounts']),
        topics: _strings(json['topics']),
        addresses: _strings(json['addresses']),
      );
}

class MailApi {
  MailApi(this._api);
  final ApiClient _api;

  Future<MailPage> bundles(String spaceId, {int page = 0, int size = 25}) async {
    final data = await _api.get('/api/mail',
        query: {'spaceId': spaceId, 'page': page, 'size': size},) as Map<String, dynamic>;
    return MailPage.fromJson(data);
  }

  Future<List<TroveDocument>> thread(String spaceId, String bundleId) async {
    final data = await _api.get('/api/documents/mail-bundle',
        query: {'spaceId': spaceId, 'bundleId': bundleId},) as List;
    return data
        .map((e) => TroveDocument.fromJson((e as Map).cast<String, dynamic>()))
        .toList();
  }
}

final mailApiProvider = Provider<MailApi>(
  (ref) => MailApi(ref.watch(apiClientProvider)),
);
