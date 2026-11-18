<?php
// Friendships (Assignment 3): shared by the friends/, users/, search and notifications endpoints.
//
//   friend_requests (from_id, to_id)   "from asked to"
//   friends (user_id, friend_id)       stored BOTH ways, so "my friends" is one simple query
//   close_friends (user_id, friend_id) only on the side that chose it
declare(strict_types=1);
require_once __DIR__ . '/api.php';
require_once __DIR__ . '/users.php';

/** How [$me] is related to [$other]: self, friends, received (they asked me), sent (I asked), none. */
function friend_status(int $me, int $other): string
{
    if ($me === $other) return 'self';
    if (row('SELECT 1 FROM friends WHERE user_id = ? AND friend_id = ?', [$me, $other])) return 'friends';
    if (row('SELECT 1 FROM friend_requests WHERE from_id = ? AND to_id = ?', [$other, $me])) return 'received';
    if (row('SELECT 1 FROM friend_requests WHERE from_id = ? AND to_id = ?', [$me, $other])) return 'sent';
    return 'none';
}

function is_close_friend(int $me, int $other): bool
{
    return row('SELECT 1 FROM close_friends WHERE user_id = ? AND friend_id = ?', [$me, $other]) !== null;
}

/** Ids of [$uid]'s friends, newest friendship first. */
function friend_ids(int $uid): array
{
    $list = rows('SELECT friend_id FROM friends WHERE user_id = ? ORDER BY created_at DESC', [$uid]);
    return array_map(fn($r) => (int) $r['friend_id'], $list);
}

/** People who are friends with both [$a] and [$b]. */
function mutual_ids(int $a, int $b): array
{
    $list = rows(
        'SELECT f1.friend_id FROM friends f1
         JOIN friends f2 ON f2.friend_id = f1.friend_id AND f2.user_id = ?
         WHERE f1.user_id = ?',
        [$b, $a]
    );
    return array_map(fn($r) => (int) $r['friend_id'], $list);
}

/** Several users as API JSON, in the same order as [$ids]. */
function users_by_ids(array $ids, int $viewerId): array
{
    if (!$ids) return [];
    $marks = implode(',', array_fill(0, count($ids), '?'));
    $byId = [];
    foreach (rows("SELECT * FROM users WHERE id IN ($marks)", array_values($ids)) as $u) {
        $byId[(int) $u['id']] = user_json($u, $viewerId);
    }
    $result = [];
    foreach ($ids as $id) {
        if (isset($byId[(int) $id])) $result[] = $byId[(int) $id];
    }
    return $result;
}

/** SQL that leaves out me, my friends and anyone with a request either way (suggestions). */
function not_connected_sql(string $column): string
{
    return "$column <> ?
        AND $column NOT IN (SELECT friend_id FROM friends WHERE user_id = ?)
        AND $column NOT IN (SELECT to_id FROM friend_requests WHERE from_id = ?)
        AND $column NOT IN (SELECT from_id FROM friend_requests WHERE to_id = ?)";
}

/** Adds a row to [$to]'s Notifications screen (18). Nothing is sent to yourself. */
function add_notification(int $to, int $from, string $type, ?int $postId = null, string $text = ''): void
{
    if ($to === $from) return;
    exec_sql(
        'INSERT INTO notifications (user_id, from_id, type, post_id, text, created_at) VALUES (?, ?, ?, ?, ?, ?)',
        [$to, $from, $type, $postId, mb_substr($text, 0, 300), now_ms()]
    );
}

/** Makes two people friends (both rows) and removes the requests between them. */
function make_friends(int $a, int $b): void
{
    $now = now_ms();
    exec_sql('INSERT IGNORE INTO friends (user_id, friend_id, created_at) VALUES (?, ?, ?), (?, ?, ?)', [$a, $b, $now, $b, $a, $now]);
    exec_sql('DELETE FROM friend_requests WHERE (from_id = ? AND to_id = ?) OR (from_id = ? AND to_id = ?)', [$a, $b, $b, $a]);
}
