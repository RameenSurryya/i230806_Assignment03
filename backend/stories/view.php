<?php
// POST /stories/view.php  (logged in)  body {story_id}: "seen". The author sees me in Seen by.
// Doing it twice changes nothing (INSERT IGNORE keeps the first time).
declare(strict_types=1);
require_once __DIR__ . '/../lib/stories.php';
method('POST');

$me = current_user_id();
$story = visible_story(int_param('story_id'), $me);
if ((int) $story['user_id'] !== $me) {
    exec_sql('INSERT IGNORE INTO story_views (story_id, viewer_id, viewed_at) VALUES (?, ?, ?)', [(int) $story['id'], $me, now_ms()]);
}
respond();
