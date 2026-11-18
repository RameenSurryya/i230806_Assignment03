<?php
// GET /auth/me.php  (logged in)  ->  {user}: my own profile (with email, phone, setup_done).
declare(strict_types=1);
require_once __DIR__ . '/../lib/api.php';
require_once __DIR__ . '/../lib/users.php';
method('GET');

$id = current_user_id();
respond(['user' => user_json(find_user($id), $id)]);