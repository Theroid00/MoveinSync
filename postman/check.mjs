// Offline checks for collection coverage, script syntax, and role-specific token acquisition.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
const collection = JSON.parse(fs.readFileSync(new URL('./collection.json', import.meta.url)));
const environment = JSON.parse(fs.readFileSync(new URL('./environment.json', import.meta.url)));
assert.equal(collection.item.length, 29);
assert.equal(new Set(collection.item.map(i => i.request.method + i.request.url)).size, 29);
const code = collection.event[0].script.exec.join('\n');
for (const item of collection.item) {
  for (const event of item.event || []) new vm.Script(event.script.exec.join('\n'));
}
const values = Object.fromEntries(environment.values.map(v => [v.key, v.value]));
for (const [role, user] of Object.entries({HR:'hr', ADMIN:'admin', OPS:'ops', EMPLOYEE:'emp001', PUBLIC:null, SERVICE:null})) {
  let authorization, credentials;
  const pm = { info:{requestName: role + ' | test'}, environment:{get:k => values[k]},
    variables:{set(){}}, request:{headers:{upsert:h => { authorization = h.value; }}},
    sendRequest(request, done) { credentials = JSON.parse(request.body.raw); done(null, {code:200,json:() => ({accessToken:'fixture-token'})}); }
  };
  if (role === 'SERVICE') {
    assert.throws(() => vm.runInNewContext(code, {pm}), /trusted SERVICE token/);
    values.service_token = 'trusted-fixture';
    vm.runInNewContext(code, {pm});
    assert.equal(authorization, 'Bearer trusted-fixture');
  } else {
    vm.runInNewContext(code, {pm});
    if (user) { assert.equal(credentials.username, user + '@acme'); assert.equal(authorization, 'Bearer fixture-token'); }
    else assert.equal(authorization, undefined);
  }
}
console.log('PASS: 29 endpoint requests, response script syntax, four role logins, public requests, trusted-service boundary');
