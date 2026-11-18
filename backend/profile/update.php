<?php
// POST /profile/update.php  (logged in, multipart or JSON)
// Fields: name, username, bio, website, work, study, city, phone (all optional: only the ones
// sent are changed). Files: photo (profile picture), cover (cover photo).
// The username must be unique: 3-20 small letters, numbers, dots or underscores.
// Saving sets setup_done = 1 (first-time setup finished). Answer: {user}.
declare(strict_types=1);
require_once __DIR__ . '/../lib/api.php';
require_once __DIR__ . '/../lib/users.php';
method('POST');

$id = current_user_id();
$old = find_user($id);
$set = ['setup_done = 1'];
$args = [];

foreach (['name', 'bio', 'website', 'work', 'study', 'city', 'phone'] as $field) {
    $value = param($field);
    if ($value !== null) {
        if ($field === 'name' && $value === '') fail('Name can\'t be empty');
        $set[] = "$field = ?";
        $args[] = $value;
    }
}

$username = param('username');
if ($username !== null) {
    $username = strtolower($username);
    if (!preg_match('/^[a-z0-9._]{3,20}$/', $username)) {
        fail('Username: 3-20 small letters, numbers, dots or underscores');
    }
    if (row('SELECT id FROM users WHERE username = ? AND id <> ?', [$username, $id]) !== null) {
        fail('That username is taken', 409);
    }
    $set[] = 'username = ?';
    $args[] = $username;
}

// New pictures replace the old files on disk.
if ($photo = upload('photo', 'profile')) {
    $set[] = 'photo_path = ?';
    $args[] = $photo['path'];
    delete_file($old['photo_path']);
}
if ($cover = upload('cover', 'cover')) {
    $set[] = 'cover_path = ?';
    $args[] = $cover['path'];
    delete_file($old['cover_path']);
}

try {
    exec_sql('UPDATE users SET ' . implode(', ', $set) . ' WHERE id = ?', [...$args, $id]);
} catch (PDOException $e) {
    // The UNIQUE index catches two people saving the same username at the same moment.
    if ($e->getCode() === '23000') fail('That username is taken', 409);
    throw $e;
}

respond(['user' => user_json(find_user($id), $id)]);