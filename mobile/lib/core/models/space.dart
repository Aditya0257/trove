/// Space — a personal or shared container of documents.
/// Mirrors backend SpaceResponse {id, name, kind, createdBy, createdAt}.
library;

class Space {
  const Space({
    required this.id,
    required this.name,
    required this.kind,
  });

  final String id;
  final String name;
  final String kind; // "personal" | "shared"

  bool get isPersonal => kind.toLowerCase() == 'personal';

  factory Space.fromJson(Map<String, dynamic> json) => Space(
        id: json['id'] as String,
        name: json['name'] as String,
        kind: (json['kind'] as String?) ?? 'personal',
      );
}
