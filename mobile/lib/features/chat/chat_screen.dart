/// ============================================================================
///  ChatScreen - "Ask your vault" natural-language Q&A over a space's documents
/// ============================================================================
///
///  Purpose
///  -------
///  A single conversational surface where the user types a plain-language question
///  ("my last water bill", "all Nike purchases") and gets back an answer with the
///  source documents it drew from. A refresh action rebuilds the space's index.
/// ============================================================================
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/notice/notice.dart';
import '../../core/notice/notice_center.dart';
import 'chat_api.dart';

class ChatScreen extends ConsumerStatefulWidget {
  const ChatScreen({required this.spaceId, super.key});
  final String spaceId;

  @override
  ConsumerState<ChatScreen> createState() => _ChatScreenState();
}

class _ChatScreenState extends ConsumerState<ChatScreen> {
  final _question = TextEditingController();
  ChatAnswer? _lastAnswer;
  bool _busy = false;

  ChatApi get _api => ref.read(chatApiProvider);

  @override
  void dispose() {
    _question.dispose();
    super.dispose();
  }

  void _toast(NoticeLevel level, String code, String message) {
    NoticeCenter.instance.show(Notice.local(level: level, code: code, userMessage: message));
  }

  Future<void> _send() async {
    final q = _question.text.trim();
    if (q.isEmpty) {
      _toast(NoticeLevel.warning, 'ASK_EMPTY', 'Type a question to ask your vault.');
      return;
    }
    setState(() => _busy = true);
    try {
      final answer = await _api.ask(widget.spaceId, q);
      if (!mounted) return;
      setState(() {
        _lastAnswer = answer;
        _question.clear();
      });
    } catch (_) {
      // The API client already surfaces failures through the Notice System.
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _reindex() async {
    setState(() => _busy = true);
    try {
      final count = await _api.reindex(widget.spaceId);
      if (!mounted) return;
      _toast(NoticeLevel.success, 'REINDEXED', 'Indexed $count documents.');
    } catch (_) {
      // The API client already surfaces failures through the Notice System.
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(
        title: const Text('Ask your vault'),
        actions: [
          IconButton(
            onPressed: _busy ? null : _reindex,
            icon: const Icon(Icons.refresh),
            tooltip: 'Rebuild index',
          ),
        ],
      ),
      body: Column(
        children: [
          Expanded(
            child: _lastAnswer == null
                ? _emptyState(scheme)
                : ListView(
                    padding: const EdgeInsets.all(16),
                    children: [
                      _answerCard(scheme, _lastAnswer!),
                    ],
                  ),
          ),
          _composer(scheme),
        ],
      ),
    );
  }

  Widget _emptyState(ColorScheme scheme) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.chat_bubble_outline, size: 48, color: scheme.onSurfaceVariant),
            const SizedBox(height: 16),
            Text(
              'Ask about your bills, receipts, warranties or reminders in plain language.',
              textAlign: TextAlign.center,
              style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 15),
            ),
          ],
        ),
      ),
    );
  }

  Widget _answerCard(ColorScheme scheme, ChatAnswer answer) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SelectableText(
              answer.answer.isEmpty ? 'No answer was returned.' : answer.answer,
              style: const TextStyle(fontSize: 15, height: 1.4),
            ),
            if (answer.sources.isNotEmpty) ...[
              const SizedBox(height: 16),
              Text(
                'Sources',
                style: TextStyle(
                  color: scheme.onSurfaceVariant,
                  fontSize: 12,
                  fontWeight: FontWeight.w700,
                  letterSpacing: 0.5,
                ),
              ),
              const SizedBox(height: 8),
              for (final source in answer.sources) _sourceRow(scheme, source),
            ],
          ],
        ),
      ),
    );
  }

  Widget _sourceRow(ColorScheme scheme, ChatCitation source) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            '[${source.index}] ${source.title}',
            style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600),
          ),
          if (source.snippet != null && source.snippet!.isNotEmpty) ...[
            const SizedBox(height: 2),
            Text(
              source.snippet!,
              style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 12, height: 1.3),
            ),
          ],
        ],
      ),
    );
  }

  Widget _composer(ColorScheme scheme) {
    return SafeArea(
      top: false,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.end,
          children: [
            Expanded(
              child: TextField(
                controller: _question,
                enabled: !_busy,
                minLines: 1,
                maxLines: 4,
                textInputAction: TextInputAction.send,
                onSubmitted: (_) => _busy ? null : _send(),
                decoration: const InputDecoration(
                  hintText: 'Ask a question...',
                ),
              ),
            ),
            const SizedBox(width: 8),
            _busy
                ? const Padding(
                    padding: EdgeInsets.all(12),
                    child: SizedBox(
                      width: 24,
                      height: 24,
                      child: CircularProgressIndicator(strokeWidth: 2.5),
                    ),
                  )
                : IconButton.filled(
                    onPressed: _send,
                    icon: const Icon(Icons.send),
                    tooltip: 'Send',
                  ),
          ],
        ),
      ),
    );
  }
}
