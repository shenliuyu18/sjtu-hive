import type JSZip from 'jszip';

export type SlideVideo = {
  index: number;
  part: string;
  x: number;
  y: number;
  width: number;
  height: number;
};

type Rel = { target: string; type: string };

const parser = new DOMParser();

function parseXml(text: string | undefined): Document | null {
  if (!text) return null;
  const doc = parser.parseFromString(text, 'application/xml');
  return doc.getElementsByTagName('parsererror').length ? null : doc;
}

function relsName(part: string): string {
  const index = part.lastIndexOf('/');
  return `${part.slice(0, index)}/_rels/${part.slice(index + 1)}.rels`;
}

function resolve(baseDir: string, target: string): string {
  if (target.startsWith('/')) return target.slice(1);
  const parts = baseDir.split('/').filter(Boolean);
  for (const segment of target.split('/')) {
    if (segment === '..') parts.pop();
    else if (segment && segment !== '.') parts.push(segment);
  }
  return parts.join('/');
}

function rels(doc: Document | null): Map<string, Rel> {
  const map = new Map<string, Rel>();
  doc?.querySelectorAll('Relationship').forEach((node) => {
    const id = node.getAttribute('Id');
    const target = node.getAttribute('Target');
    if (id && target) map.set(id, { target, type: node.getAttribute('Type') ?? '' });
  });
  return map;
}

function first<T extends Element>(parent: Element | Document | null | undefined, tag: string): T | null {
  return (parent?.getElementsByTagName(tag)[0] as T | undefined) ?? null;
}

export async function findSlideVideos(zip: JSZip): Promise<SlideVideo[]> {
  const presentation = parseXml(await zip.file('ppt/presentation.xml')?.async('string'));
  if (!presentation) return [];
  const size = first<Element>(presentation, 'p:sldSz');
  const slideWidth = Number(size?.getAttribute('cx') ?? 0) || 9144000;
  const slideHeight = Number(size?.getAttribute('cy') ?? 0) || 6858000;

  const presentationRels = rels(parseXml(await zip.file('ppt/_rels/presentation.xml.rels')?.async('string')));
  const order = Array.from(presentation.getElementsByTagName('p:sldId'))
    .map((node) => {
      const id = node.getAttribute('r:id');
      const target = id ? presentationRels.get(id)?.target : undefined;
      return target ? resolve('ppt', target) : null;
    })
    .filter((value): value is string => Boolean(value));

  const result: SlideVideo[] = [];
  for (let index = 0; index < order.length; index += 1) {
    const part = order[index];
    const slideRels = rels(parseXml(await zip.file(relsName(part))?.async('string')));
    const slide = parseXml(await zip.file(part)?.async('string'));
    if (!slide) continue;
    const baseDir = part.slice(0, part.lastIndexOf('/'));
    for (const picture of Array.from(slide.getElementsByTagName('p:pic'))) {
      const holder = first<Element>(picture, 'p:nvPr');
      const link = first<Element>(holder, 'a:videoFile')?.getAttribute('r:link');
      const embed = first<Element>(holder, 'p14:media')?.getAttribute('r:embed');
      const target = slideRels.get(link ?? '')?.target ?? slideRels.get(embed ?? '')?.target;
      if (!target) continue;
      const transform = first<Element>(first<Element>(picture, 'p:spPr'), 'a:xfrm');
      const offset = first<Element>(transform, 'a:off');
      const extent = first<Element>(transform, 'a:ext');
      if (!offset || !extent) continue;
      const x = Number(offset.getAttribute('x') ?? 0) / slideWidth;
      const y = Number(offset.getAttribute('y') ?? 0) / slideHeight;
      const width = Number(extent.getAttribute('cx') ?? 0) / slideWidth;
      const height = Number(extent.getAttribute('cy') ?? 0) / slideHeight;
      result.push({
        index,
        part: resolve(baseDir, target),
        x: Math.max(0, Math.min(1, x)),
        y: Math.max(0, Math.min(1, y)),
        width: Math.max(0.05, Math.min(1, width)),
        height: Math.max(0.05, Math.min(1, height)),
      });
      break;
    }
  }
  return result;
}
