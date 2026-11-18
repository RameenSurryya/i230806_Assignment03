<?php
// Users and sessions: what a user looks like in API answers, and creating login tokens.
declare(strict_types=1);

/** Online = active in the last 2 minutes (every request from the app counts, see current_user_id()). */
const ONLINE_WINDOW_MS = 2 * 60 * 1000;

function is_online(array $user): bool
{
    return (int) $user['is_online'] === 1 && now_ms() - (int) $user['last_seen'] < ONLINE_WINDOW_MS;
}

/** A user as the app sees them. Email and phone only for yourself. */
function user_json(array $u, int $viewerId = 0): array
{
    $json = [
        'id' => (int) $u['id'],
        'name' => $u['name'],
        'username' => $u['username'] ?? '',
        'bio' => $u['bio'],
        'website' => $u['website'],
        'work' => $u['work'],
        'study' => $u['study'],
        'city' => $u['city'],
        'photo_url' => media_url($u['photo_path']),
        'cover_url' => media_url($u['cover_path']),
        'online' => is_online($u),
        'last_seen' => (int) $u['last_seen'],
        'created_at' => (int) $u['created_at'],
    ];
    if ($viewerId === (int) $u['id']) {
        $json['email'] = $u['email'];
        $json['phone'] = $u['phone'];
        $json['gender'] = $u['gender'];
        $json['birthday'] = $u['birthday'];
        $json['setup_done'] = (int) $u['setup_done'] === 1;
    }
    return $json;
}

function find_user(int $id): ?array
{
    return row('SELECT * FROM users WHERE id = ?', [$id]);
}

/** Makes a new login: a random 64-character token. Only its SHA-256 hash is stored. */
function create_session(int $userId): string
{
    $token = bin2hex(random_bytes(32));
    exec_sql(
        'INSERT INTO sessions (token_hash, user_id, created_at) VALUES (?, ?, ?)',
        [hash('sha256', $token), $userId, now_ms()]
    );
    exec_sql('UPDATE users SET is_online = 1, last_seen = ? WHERE id = ?', [now_ms(), $userId]);
    return $token;
}