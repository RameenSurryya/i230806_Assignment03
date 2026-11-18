<?php
// POST /friends/action.php  (logged in)  body {action, user_id}  ->  {status, close}
//
// action:  request   send a friend request (if they already asked me, we simply become friends)
//          cancel    take back a request I sent
//          accept    they asked me: become friends (both rows) and tell them
//          decline   refuse their request (they are not told)
//          unfriend  end the friendship on both sides (and close friends)
//          close / unclose   add or remove them from my close friends (friends only)
//
// Sent through the app's offline queue with an X-Request-Id, so once() makes a repeat harmless.
// The answer is the new relationship, so the screen can show the right button.
declare(strict_types=1);
require_once __DIR__ . '/../lib/friends.php';
method('POST');

$me = current_user_id();
$action = required('action');
$other = int_param('user_id');
if (!in_array($action, ['request', 'cancel', 'accept', 'decline', 'unfriend', 'close', 'unclose'], true)) {
    fail('Unknown action');
}
if ($other === $me || find_user($other) === null) fail('User not found', 404);

once($me, function () use ($me, $other, $action) {
    $status = friend_status($me, $other);
    switch ($action) {
        case 'request':
            if ($status === 'received') {                 // both asked: friends straight away
                make_friends($me, $other);
                add_notification($other, $me, 'friend_accepted');
            } elseif ($status === 'none') {
                exec_sql('INSERT IGNORE INTO friend_requests (from_id, to_id, created_at) VALUES (?, ?, ?)', [$me, $other, now_ms()]);
                add_notification($other, $me, 'friend_request');
            }
            break;
        case 'cancel':
            exec_sql('DELETE FROM friend_requests WHERE from_id = ? AND to_id = ?', [$me, $other]);
            break;
        case 'accept':
            if ($status === 'received') {
                make_friends($me, $other);
                add_notification($other, $me, 'friend_accepted');
            }
            break;
        case 'decline':
            exec_sql('DELETE FROM friend_requests WHERE from_id = ? AND to_id = ?', [$other, $me]);
            break;
        case 'unfriend':
            exec_sql('DELETE FROM friends WHERE (user_id = ? AND friend_id = ?) OR (user_id = ? AND friend_id = ?)', [$me, $other, $other, $me]);
            exec_sql('DELETE FROM close_friends WHERE (user_id = ? AND friend_id = ?) OR (user_id = ? AND friend_id = ?)', [$me, $other, $other, $me]);
            break;
        case 'close':
            if ($status === 'friends') exec_sql('INSERT IGNORE INTO close_friends (user_id, friend_id) VALUES (?, ?)', [$me, $other]);
            break;
        case 'unclose':
            exec_sql('DELETE FROM close_friends WHERE user_id = ? AND friend_id = ?', [$me, $other]);
            break;
    }
    return ['status' => friend_status($me, $other), 'close' => is_close_friend($me, $other)];
});
