const runTest = async (parameters) =>  {
	const baseUrl = window.location.origin;
	const url = new URL(`${window.location.pathname.split('/')[1]}/rest/runTest/`, baseUrl);
	return fetch(url.toString(), {
		method: 'POST', 
		headers : new Headers({
 			'Content-Type': 'application/json'
		}),
		body: JSON.stringify({
			environmentFile : parameters.environmentFile,
			collectionFile : parameters.collectionFile
		})
	});
}

const runTestForm = (container) => {
	const html = `<form id='runTest-form'>
		<div id='runTest-environmentFile-form-field'>
			<label for='environmentFile'>environmentFile</label>
			<input type='text' id='runTest-environmentFile-param' name='environmentFile'/>
		</div>
		<div id='runTest-collectionFile-form-field'>
			<label for='collectionFile'>collectionFile</label>
			<input type='text' id='runTest-collectionFile-param' name='collectionFile'/>
		</div>
		<button type='button'>Test</button>
	</form>`;

	container.insertAdjacentHTML('beforeend', html)

	const environmentFile = container.querySelector('#runTest-environmentFile-param');
	const collectionFile = container.querySelector('#runTest-collectionFile-param');

	container.querySelector('#runTest-form button').onclick = () => {
		const params = {
			environmentFile : environmentFile.value !== "" ? environmentFile.value : undefined,
			collectionFile : collectionFile.value !== "" ? collectionFile.value : undefined
		};

		runTest(params).then(r => r.text().then(
				t => alert(t)
			));
	};
}

export { runTest, runTestForm };