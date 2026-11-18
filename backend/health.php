<?php
// GET /kinnect/health.php: checks that PHP runs, the database connects and the tables exist.
// Open it in the laptop's browser and on the phone to test the connection.
declare(strict_types=1);
require_once __DIR__ . '/lib/api.php';

respond([
    'server' => 'Kinnect API',
    'php' => PHP_VERSION,
    'database' => config()['db_name'],
    'tables' => count(rows('SHOW TABLES')),   // 19 when schema.sql was imported
    'time' => now_ms(),
]);