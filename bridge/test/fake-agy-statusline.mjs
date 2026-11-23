#!/usr/bin/env node
// Pretends to be the agy TUI: runs the statusLine command from --settings with a sample status JSON.
import { execFileSync } from 'node:child_process';
const i = process.argv.indexOf('--settings');
const s = JSON.parse(process.argv[i + 1]);
const status = { plan_tier: 'Google AI Pro', email: 'someone@example.com', quota: {
  'gemini-5h': { remaining_fraction: 0.69, reset_time: '2026-11-23T06:21:22Z', reset_in_seconds: 28 },
  'gemini-weekly': { remaining_fraction: 0.88, reset_time: '2026-11-28T08:56:25Z' },
  '3p-5h': { remaining_fraction: 1, reset_time: '2026-11-23T11:20:53Z' },
  '3p-weekly': { remaining_fraction: 1, reset_time: '2026-11-30T06:20:53Z' },
  'gemini-argon-wtu-weekly': { remaining_fraction: 1, reset_time: '2026-11-30T06:20:53Z' } } };
setTimeout(() => {
  execFileSync('/bin/sh', ['-c', s.statusLine.command], { input: JSON.stringify(status) });
  setInterval(() => {}, 1000); // stay alive like a TUI until killed
}, 300);
