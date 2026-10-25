// Opt-in local browser verification. Both servers must be the local review setup.
// Backend: dev profile, origin http://127.0.0.1:5173 allowed; UI: Vite port 5173.
// Copy to the UI's Cypress directory temporarily, then run with chromeWebSecurity=true.
describe('SNI-41 / real browser CORS', () => {
  const backend = 'http://127.0.0.1:18080';

  it('preserves the same-origin UI and allows direct preflight and readable identity errors', () => {
    cy.intercept('GET', '/api/snippets?*').as('list');
    cy.visit('http://127.0.0.1:5173/');
    cy.wait('@list').its('response.statusCode').should('eq', 200);
    cy.window().then(async win => {
      const allowed = await win.fetch(backend + '/users', {headers: {'X-Dev-Actor-Id': 'dev-thiago'}});
      expect(allowed.status).to.eq(200);
      expect((await allowed.json()).users).to.be.an('array');
      const unauthorized = await win.fetch(backend + '/users');
      expect(unauthorized.status).to.eq(401);
      expect((await unauthorized.json()).type).to.be.a('string');
    });
    cy.screenshot('sni41-browser-allowed');
  });

  it('blocks a different origin in the browser, even with a valid development actor', () => {
    cy.visit('http://localhost:5173/');
    cy.window().then(async win => {
      let blocked = false;
      try {
        await win.fetch(backend + '/users', {headers: {'X-Dev-Actor-Id': 'dev-thiago'}});
      } catch (error) {
        blocked = true;
        expect((error as Error).name).to.eq('TypeError');
      }
      expect(blocked, 'browser must enforce CORS').to.eq(true);
    });
  });
});
