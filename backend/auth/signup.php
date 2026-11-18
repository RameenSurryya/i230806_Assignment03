<?php
// POST /auth/signup.php  {name, email, password, gender?, birthday?}
// Creates the account (password hashed with password_hash) and logs it in.
// Answer: {token, user}. The user has setup_done = false, so the app opens Edit profile next.
declare(strict_types=1);
require_once __DIR__ . '/../lib/api.php';
require_once __DIR__ . '/../lib/users.php';
method('POST');

$name = required('name');
$email = strtolower(required('email'));
$password = required('password');

if (!filter_var($email, FILTER_VALIDATE_EMAIL)) fail('Enter a valid email address');
if (strlen($password) < 6) fail('Password must be at least 6 characters');
if (row('SELECT id FROM users WHERE email = ?', [$email]) !== null) {
    fail('An account with this email already exists', 409);
}

exec_sql(
    'INSERT INTO users (email, password_hash, name, gender, birthday, created_at) VALUES (?, ?, ?, ?, ?, ?)',
    [
        $email,
        password_hash($password, PASSWORD_DEFAULT),   // bcrypt: salted and slow, never reversible
        $name,
        param('gender', ''),
        param('birthday', ''),
        now_ms(),
    ]
);
$id = (int) db()->lastInsertId();

respond(['token' => create_session($id), 'user' => user_json(find_user($id), $id)]);