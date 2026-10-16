const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

// The next version is passed by semantic-release as the first argument
const nextVersion = process.argv[2];
if (!nextVersion || !/^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/.test(nextVersion)) {
    console.error("Provide a semantic release version.");
    process.exit(1);
}

const gradlePropertiesPath = path.join(__dirname, '..', 'gradle.properties');
let content = fs.readFileSync(gradlePropertiesPath, 'utf8');

// 1. Update versionName
if (content.match(/versionName\s*=\s*/)) {
    content = content.replace(/versionName\s*=\s*.*/g, `versionName=${nextVersion}`);
    console.log(`Updated versionName to ${nextVersion}`);
} else {
    content += `\nversionName=${nextVersion}`;
    console.log(`Added versionName=${nextVersion}`);
}

// Google Play has one versionCode sequence across main and dev. Release workflows
// are serialized, and checkout fetches every tag, so include both stable/dev tags.
let greatestCode = Number(content.match(/^versionCode\s*=\s*(\d+)/m)?.[1] ?? 108);
const repository = path.join(__dirname, '..');
try {
    const tags = execFileSync('git', ['tag', '--list', 'v*'], {
        cwd: repository, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'],
    }).trim().split('\n').filter(tag => /^v\d+\.\d+\.\d+(?:-dev\.\d+)?$/.test(tag));
    for (const tag of tags) {
        try {
            const properties = execFileSync('git', ['show', `${tag}:gradle.properties`], {
                cwd: repository, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'],
            });
            const code = Number(properties.match(/^versionCode\s*=\s*(\d+)/m)?.[1] ?? 0);
            greatestCode = Math.max(greatestCode, code);
        } catch { /* Older tags may predate gradle.properties versioning. */ }
    }
} catch (failure) {
    if (fs.existsSync(path.join(repository, '.git'))) throw failure;
    // Source archives use the version counter committed in the tag.
}
const nextCode = greatestCode + 1;
if (!Number.isSafeInteger(nextCode) || nextCode > 2100000000) {
    throw new Error('Android versionCode limit exceeded');
}
content = /^versionCode\s*=/m.test(content)
    ? content.replace(/^versionCode\s*=.*$/m, `versionCode=${nextCode}`)
    : `${content}\nversionCode=${nextCode}\n`;
console.log(`Next global versionCode: ${nextCode}`);

fs.writeFileSync(gradlePropertiesPath, content, 'utf8');
console.log(`Successfully updated gradle.properties!`);

// Expose a prepared version to the same workflow. The publish job only runs
// after semantic-release has also succeeded in publishing the GitHub release.
if (process.env.GITHUB_OUTPUT) {
    fs.appendFileSync(process.env.GITHUB_OUTPUT, `release_version=${nextVersion}\n`);
}
