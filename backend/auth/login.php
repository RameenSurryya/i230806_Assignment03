<?php
// POST /auth/login.php  {email, password}
// Answer: {token, user}. The app stores the token (EncryptedSharedPreferences) and sends it
// with every request. Wrong email or password -> 401 with the same message for both
// (so nobody can find out which emails have accounts).
declare(strict_types=1);
require_once __DIR__ . '/../lib/api.php';
require_once __DIR__ . '/../lib/users.php';
method('POST');

$email = strtolower(required('email'));
$password = required('password');

$user = row('SELECT * FROM users WHERE email = ?', [$email]);
if ($user === null || !password_verify($password, $user['password_hash'])) {
    fail('Wrong email or password', 401);
}

$id = (int) $user['id'];
respond(['token' => create_session($id), 'user' => user_json(find_user($id), $id)]);