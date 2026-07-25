/// Invitation + Member - the shared-space collaboration models.
/// Mirror the backend Invitation and MemberResponse shapes.
library;

class Invitation {
  const Invitation({
    required this.spaceId,
    required this.spaceName,
    required this.role,
    this.invitedByName,
  });

  final String spaceId;
  final String spaceName;
  final String role;
  final String? invitedByName;

  factory Invitation.fromJson(Map<String, dynamic> j) => Invitation(
        spaceId: j['spaceId'] as String,
        spaceName: (j['spaceName'] as String?) ?? 'a space',
        role: (j['role'] as String?) ?? 'member',
        invitedByName: (j['invitedByName'] as String?) ?? (j['invitedByEmail'] as String?),
      );
}

class Member {
  const Member({
    required this.userId,
    required this.role,
    required this.status,
    this.email,
    this.displayName,
    this.selfRequested = false,
  });

  final String userId;
  final String role; // owner | member | viewer
  final String status; // active | pending | declined
  final String? email;
  final String? displayName;
  final bool selfRequested; // pending join requested via a link (invitedBy == null)

  bool get isActive => status == 'active';
  bool get isPending => status == 'pending';
  String get name => (displayName != null && displayName!.isNotEmpty)
      ? displayName!
      : (email ?? userId);

  factory Member.fromJson(Map<String, dynamic> j) => Member(
        userId: j['userId'] as String,
        role: (j['role'] as String?) ?? 'member',
        status: (j['status'] as String?) ?? 'active',
        email: j['email'] as String?,
        displayName: j['displayName'] as String?,
        selfRequested: j['selfRequested'] == true,
      );
}
