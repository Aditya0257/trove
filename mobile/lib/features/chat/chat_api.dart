/// ============================================================================
///  chat_api - "Ask your vault" natural-language Q&A calls (/api/chat)
/// ============================================================================
///
///  Purpose
///  -------
///  Lets a signed-in user ask questions about the documents in a space in plain
///  language and get an answer with source citations. Also exposes a reindex call
///  to (re)build the search index a space's answers draw from.
/// ============================================================================
library;

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/providers.dart';

/// One source document backing an answer.
class ChatCitation {
  const ChatCitation({
    required this.documentId,
    required this.index,
    required this.title,
    this.snippet,
  });

  final String documentId;
  final int index;
  final String title;
  final String? snippet;

  factory ChatCitation.fromJson(Map<String, dynamic> json) => ChatCitation(
        documentId: (json['documentId'] as String?) ?? '',
        index: (json['index'] as num?)?.toInt() ?? 0,
        title: (json['title'] as String?) ?? 'Untitled document',
        snippet: json['snippet'] as String?,
      );
}

/// A vault answer: the prose reply, whether a model was used, and its sources.
class ChatAnswer {
  const ChatAnswer({
    required this.answer,
    required this.aiUsed,
    required this.sources,
  });

  final String answer;
  final bool aiUsed;
  final List<ChatCitation> sources;

  factory ChatAnswer.fromJson(Map<String, dynamic> json) {
    final raw = json['sources'];
    final sources = raw is List
        ? raw
            .whereType<Map<String, dynamic>>()
            .map(ChatCitation.fromJson)
            .toList()
        : <ChatCitation>[];
    return ChatAnswer(
      answer: (json['answer'] as String?) ?? '',
      aiUsed: (json['aiUsed'] as bool?) ?? false,
      sources: sources,
    );
  }
}

class ChatApi {
  ChatApi(this._api);
  final ApiClient _api;

  /// Ask a natural-language question against the documents in [spaceId].
  Future<ChatAnswer> ask(String spaceId, String question) async {
    final data = await _api.post('/api/chat/ask',
        query: {'spaceId': spaceId},
        body: {'question': question},) as Map<String, dynamic>;
    return ChatAnswer.fromJson(data);
  }

  /// Rebuild the search index for [spaceId]; returns how many documents were indexed.
  Future<int> reindex(String spaceId) async {
    final data = await _api.post('/api/chat/reindex',
        query: {'spaceId': spaceId},) as Map<String, dynamic>;
    return (data['indexed'] as num?)?.toInt() ?? 0;
  }
}

final chatApiProvider = Provider<ChatApi>(
  (ref) => ChatApi(ref.watch(apiClientProvider)),
);
