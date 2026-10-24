// Opt-in browser acceptance: Vite must proxy /api to the SNI-54 backend.
// No intercepted/fabricated responses. Leaves one identified local test snippet.
describe('SNI-54 real language catalog', () => {
  it('offers explicit versions and recognizes .prs before a real creation', () => {
    cy.intercept('GET', '/api/languages').as('catalog');
    cy.intercept('POST', '/api/snippets').as('create');
    cy.visit('/');
    cy.wait('@catalog').then(({response}) => {
      expect(response?.statusCode).to.eq(200);
      expect(response?.body).to.deep.eq([
        {language: 'printscript', extension: 'prs', versions: ['1.0', '1.1']},
      ]);
    });
    // Wait for React Query to render its data, not only for the network response.
    cy.contains('button', 'Add Snippet').click();
    cy.contains('li', 'Create snippet').click();
    cy.get('[aria-labelledby="language-label"]').click();
    cy.get('[role="option"]').contains('printscript').click();
    cy.get('#name').type('{esc}');
    cy.contains('h5', 'Add Snippet').should('not.exist');
    const name = 'SNI-54-' + Date.now();
    cy.get('[data-testid="upload-file-input"]').selectFile({
      contents: Cypress.Buffer.from('println(54);'), fileName: name + '.prs',
    }, {force: true});
    cy.get('#name').should('have.value', name);
    cy.get('[aria-labelledby="language-label"]').should('contain.text', 'printscript');
    cy.get('[data-testid="save-snippet"]').should('be.disabled');
    cy.get('[aria-labelledby="version-label"]').click();
    cy.get('[role="option"]').should('have.length', 2);
    cy.get('[role="option"]').contains('1.0').should('exist');
    cy.get('[role="option"]').contains('1.1').click();
    cy.get('[data-testid="save-snippet"]').click();
    cy.wait('@create').then(({request, response}) => {
      expect(request.body).to.deep.eq({
        name, description: null, language: 'printscript', version: '1.1', code: 'println(54);',
      });
      expect(response?.statusCode).to.eq(201);
      expect(response?.body).to.include({name, language: 'printscript', version: '1.1'});
      cy.log('Created local SNI-54 snippet: ' + response?.body.id);
    });
    cy.contains('h5', 'Add Snippet').should('not.exist');
  });
});
