// Opt-in real local services through the UI's Vite /api proxy. No API responses are stubbed.
// This writes one named local snippet and a share; never run against production.
describe('SNI-32 / real sharing', () => {
  it('shares from the UI and enforces recipient and third-party permissions over HTTP', () => {
    const owner = 'dev-juanfran';
    const recipient = 'dev-ana';
    const name = 'SNI-32 browser ' + Date.now();
    const code = 'println(32);\r\n';
    let id: string;
    cy.request('/app-config.json').then(({body}) => {
      expect(body.environment).to.eq('dev');
      expect(body.identity).to.deep.eq({mode: 'dev', actorId: owner});
    });
    cy.request({method: 'POST', url: '/api/snippets', headers: {
      'X-Dev-Actor-Id': owner, 'Idempotency-Key': crypto.randomUUID(),
    }, body: {name, description: 'SNI-32 local verification', language: 'printscript', version: '1.1', code}})
      .then(({status, body}) => {expect(status).to.eq(201); id = body.id;});
    cy.intercept('GET', '/api/snippets?*').as('list');
    cy.intercept('GET', '/api/snippets/*').as('detail');
    cy.intercept('GET', '/api/users?*').as('users');
    cy.intercept('PUT', '/api/snippets/*/shares/*').as('share');
    cy.visit('/'); cy.wait('@list');
    cy.get('input[placeholder="Nombre del snippet…"]').type(name);
    cy.wait('@list'); cy.contains('button', name).click(); cy.wait('@detail');
    cy.get('#detail-code').should('have.value', 'println(32);\n');
    cy.contains('button', 'Compartir').click(); cy.wait('@users');
    cy.get('input[role="combobox"]').type('Ana'); cy.wait('@users');
    cy.get('[role="option"]').contains('Ana').click();
    cy.get('[data-testid="confirm-share"]').should('be.enabled').click();
    cy.wait('@share').then(({request, response}) => {
      expect(request.url).to.include('/shares/' + recipient);
      expect(response?.statusCode).to.eq(201);
      expect(response?.body).to.deep.eq({snippetId: id, userId: recipient});
    });
    cy.contains('[role="dialog"]', 'Compartir snippet').should('not.exist');
    cy.then(() => {
      cy.request({method: 'PUT', url: '/api/snippets/' + id + '/shares/' + recipient,
        headers: {'X-Dev-Actor-Id': owner}}).its('status').should('eq', 200);
      cy.request({url: '/api/snippets/' + id, headers: {'X-Dev-Actor-Id': recipient}}).then(({status, body}) => {
        expect(status).to.eq(200); expect(body.code).to.eq(code);
        expect(body.relation).to.eq('shared'); expect(body.capabilities).to.deep.eq({modify: false, share: false});
      });
      cy.request({url: '/api/snippets?relation=shared&page=0&page_size=100', headers: {'X-Dev-Actor-Id': recipient}})
        .then(({body}) => expect(body.snippets.filter((item: {id: string}) => item.id === id)).to.have.length(1));
      cy.request({method: 'PUT', url: '/api/snippets/' + id, headers: {'X-Dev-Actor-Id': recipient},
        body: {name, description: null, language: 'printscript', version: '1.1', code: 'println(99);'}, failOnStatusCode: false})
        .its('status').should('eq', 403);
      cy.request({method: 'PUT', url: '/api/snippets/' + id + '/shares/dev-bruno',
        headers: {'X-Dev-Actor-Id': recipient}, failOnStatusCode: false}).its('status').should('eq', 403);
      cy.request({url: '/api/snippets/' + id, headers: {'X-Dev-Actor-Id': 'dev-bruno'}, failOnStatusCode: false})
        .its('status').should('eq', 404);
    });
    // Change only the public dev identity config; all /api responses remain real.
    cy.intercept('GET', '/app-config.json', {environment: 'dev', identity: {mode: 'dev', actorId: recipient}});
    cy.reload(); cy.wait('@list');
    cy.get('input[placeholder="Nombre del snippet…"]').type(name);
    cy.contains('button', name).click(); cy.wait('@detail');
    cy.get('#detail-code').should('have.value', 'println(32);\n').and('have.attr', 'readonly');
    cy.get('[data-testid="save-changes"]').should('be.disabled');
    cy.contains('button', 'Compartir').should('be.disabled');
    cy.contains('Solo lectura.').should('be.visible');
    cy.screenshot('sni32-real-sharing', {capture: 'viewport'});
  });
});
