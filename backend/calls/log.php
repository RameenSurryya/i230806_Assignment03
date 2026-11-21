<?php
// GET /calls/log.php?limit=50  (logged in)  ->  {calls: [...]}  my call history, newest first.
// Each: the call (type, status, outgoing, created_at, duration_sec) and the other person.
declare(strict_types=1);
require_once __DIR__ . '/../lib/calls.php';
method('GET');

$me = current_user_id();
expire_unanswered_calls();
$limit = max(1, min(200, int_param('limit', 50)));
$list = rows("SELECT * FROM calls WHERE caller_id = ? OR callee_id = ? ORDER BY created_at DESC LIMIT $limit", [$me, $me]);
$others = array_map(fn($c) => (int) $c['caller_id'] === $me ? (int) $c['callee_id'] : (int) $c['caller_id'], $list);
$users = [];
foreach (users_by_ids(array_values(array_unique($others)), $me) as $u) $users[$u['id']] = $u;
$result = [];
foreach ($list as $i => $c) $result[] = call_json($c, $me) + ['other' => $users[$others[$i]] ?? null];
respond(['calls' => $result]);
