function initOsceMenu() {
  const catalogue = OSCE_CATALOGUE;
  let selected = null;
  const byId = new Map();
  for (const groups of Object.values(catalogue)) {
    for (const topics of Object.values(groups)) {
      for (const topic of topics) byId.set(topic.file, topic);
    }
  }
  const categories = document.getElementById('categories');
  const conditionPage = document.getElementById('condition-page');
  const topicsRoot = document.getElementById('topics');
  const count = document.getElementById('count');
  const start = document.getElementById('start');
  const status = document.getElementById('status');
  let currentTopics = [...byId.values()];
  function choose(topic) {
    selected = topic;
    document.getElementById('selection').textContent = topic.title;
    start.disabled = false;
    status.textContent = 'Ready · local notes · no AI called yet';
    for (const button of topicsRoot.querySelectorAll('.condition')) {
      button.setAttribute('aria-pressed', String(button.dataset.source === topic.file));
    }
  }
  function showSpecialty(area, specialty) {
    currentTopics = catalogue[area][specialty];
    categories.hidden = true;
    conditionPage.hidden = false;
    document.getElementById('specialty-title').textContent = specialty;
    topicsRoot.replaceChildren();
    for (const topic of currentTopics) {
      const button = document.createElement('button');
      button.className = 'condition';
      button.dataset.source = topic.file;
      button.textContent = topic.title;
      button.setAttribute('aria-pressed', String(selected?.file === topic.file));
      button.onclick = () => choose(topic);
      topicsRoot.append(button);
    }
  }
  for (const [area, specialties] of Object.entries(catalogue)) {
    const heading = document.createElement('h2');
    heading.textContent = area;
    const grid = document.createElement('div');
    grid.className = 'specialties';
    for (const [specialty, topics] of Object.entries(specialties)) {
      const button = document.createElement('button');
      button.dataset.area = area;
      button.dataset.specialty = specialty;
      button.append(document.createTextNode(specialty));
      const total = document.createElement('small');
      total.textContent = topics.length;
      button.append(total);
      button.setAttribute('aria-label', specialty + ' (' + area + ')');
      button.onclick = () => showSpecialty(area, specialty);
      grid.append(button);
    }
    categories.append(heading, grid);
  }
  document.getElementById('back').onclick = () => {
    categories.hidden = false;
    conditionPage.hidden = true;
    currentTopics = [...byId.values()];
  };
  count.oninput = () => {document.getElementById('count-value').textContent = count.value;};
  document.getElementById('random').onclick = () => {
    const topic = currentTopics[Math.floor(Math.random() * currentTopics.length)];
    if (categories.hidden === false) {
      for (const [area, groups] of Object.entries(catalogue)) {
        for (const [specialty, topics] of Object.entries(groups)) {
          if (topics.includes(topic)) showSpecialty(area, specialty);
        }
      }
    }
    choose(topic);
  };
  start.onclick = async () => {
    if (start.disabled || !selected || byId.get(selected.file) !== selected) return;
    const questions = Number(count.value);
    if (!Number.isInteger(questions) || questions < 1 || questions > 40) return;
    start.disabled = true;
    try {
      const sent = await Websandbox.connection.remote.sendPrompt({text:
        'Begin the Zero to Finals progressive clinical case exercise.\n' +
        'Condition: ' + selected.title + '\n' +
        'Questions: ' + questions + '\n' +
        'LOCAL_SOURCE_FILE: ' + selected.file + '\n' +
        'Source provenance: ' + selected.url + '\n' +
        'Use only the attached downloaded local source for this exercise. ' +
        'Do not fetch websites, search the web, use MCP or read other condition files. ' +
        'Ask one question at a time and wait for my answer.', sourceFiles: [selected.file]});
      status.textContent = sent === false ? 'Not sent. Try again.' : 'Case requested · continue in chat';
    } catch (error) {status.textContent = 'Not sent. Try again.';}
    finally {start.disabled = false;}
  };
}
