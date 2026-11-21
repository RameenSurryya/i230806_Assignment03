<?php
// POST /stories/react.php  (logged in)  body {story_id, type}  type = like | love | haha ...
// Saves my reaction (and marks the story seen). The author gets a notification the first
// time I react to this story; changing the reaction later does not notify again.
declare(strict_types=1);
require_once __DIR__ . '/../lib/stories.php';
method('POST');

$me = current_user_id();
$story = visible_story(int_param('story_id'), $me);
$type = required('type');
if (!in_array($type, ['like', 'love', 'haha', 'wow', 'sad', 'angry'], true)) fail('Unknown reaction');
$author = (int) $story['user_id'];
if ($author === $me) fail('You cannot react to your own story');

once($me, function () use ($me, $story, $type, $author) {
    $id = (int) $story['id'];
    $old = row('SELECT reaction FROM story_views WHERE story_id = ? AND viewer_id = ?', [$id, $me]);
    exec_sql(
        'INSERT INTO story_views (story_id, viewer_id, viewed_at, reaction) VALUES (?, ?, ?, ?)
         ON DUPLICATE KEY UPDATE reaction = VALUES(reaction)',
        [$id, $me, now_ms(), $type]
    );
    if ($old === null || $old['reaction'] === null) add_notification($author, $me, 'story_reaction', null, $type);
    return ['reaction' => $type];
});
