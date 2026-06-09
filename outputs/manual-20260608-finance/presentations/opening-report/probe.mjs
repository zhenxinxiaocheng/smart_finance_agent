import { Presentation } from '@oai/artifact-tool';
const p = Presentation.create({ slideSize: { width: 1280, height: 720 } });
function methods(o){ return Object.getOwnPropertyNames(Object.getPrototypeOf(o)).filter(x=>x!=='constructor'); }
console.log('slides methods', methods(p.slides));
const s = p.slides.add();
console.log('slide methods', methods(s));
console.log('shapes methods', methods(s.shapes));
console.log('images methods', methods(s.images));
console.log('text?', s.text);
