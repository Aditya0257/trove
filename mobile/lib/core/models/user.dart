/// AuthUser — the identity returned by /api/auth/login|register.
/// Mirrors backend AuthResponse {token, userId, email, displayName}.
library;

class AuthUser {
  const AuthUser({
    required this.userId,
    required this.email,
    this.displayName,
    this.admin = false,
  });

  final String userId;
  final String email;
  final String? displayName;
  final bool admin;

  String get shortName =>
      (displayName != null && displayName!.isNotEmpty) ? displayName! : email;

  factory AuthUser.fromJson(Map<String, dynamic> json) => AuthUser(
        userId: json['userId'] as String,
        email: json['email'] as String,
        displayName: json['displayName'] as String?,
        admin: (json['admin'] as bool?) ?? false,
      );

  Map<String, dynamic> toJson() => {
        'userId': userId,
        'email': email,
        'displayName': displayName,
        'admin': admin,
      };
}
