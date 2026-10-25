// Run with the backend allowlist EMPTY and the real Vite /api proxy.
// Creates one named local snippet; never use against production.
describe('SNI-41 / empty allowlist proxy regression', () => {
  it('creates and updates through the same-origin UI proxy without CORS grants', () => {
    cy.visit('http://127.0.0.1:5173/');
    cy.request('/app-config.json').then(({body: config}) => {
      expect(config.identity.mode).to.eq('dev');
      cy.window().then(async win => {
        const draft = {name: 'SNI-41 proxy ' + Date.now(), description: 'CORS proxy regression',
          language: 'printscript', version: '1.1', code: 'println(41);\n'};
        const created = await win.fetch('/api/snippets', {method: 'POST',
          headers: {'Content-Type': 'application/json', 'X-Dev-Actor-Id': config.identity.actorId,
            'Idempotency-Key': win.crypto.randomUUID()}, body: JSON.stringify(draft)});
        expect(created.status).to.eq(201);
        const {id} = await created.json();
        const updated = await win.fetch('/api/snippets/' + id, {method: 'PUT',
          headers: {'Content-Type': 'application/json', 'X-Dev-Actor-Id': config.identity.actorId},
          body: JSON.stringify({...draft, code: 'println(42);\n'})});
        expect(updated.status).to.eq(200);
        const detail = await win.fetch('/api/snippets/' + id,
          {headers: {'X-Dev-Actor-Id': config.identity.actorId}});
        expect(detail.status).to.eq(200);
        expect((await detail.json()).code).to.eq('println(42);\n');
      });
    });
  });
});
