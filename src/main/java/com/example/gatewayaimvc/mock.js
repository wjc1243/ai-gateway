require('http').createServer((req, res) => {
    setTimeout(() => {
        res.writeHead(200, {'Content-Type': 'application/json'});
        res.end('{"ok":true,"slept":2}');
    }, 2000);
}).listen(9000, () => console.log('mock on 9000'));