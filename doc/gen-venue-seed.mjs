// 공연장 JSON(doc/venue-seed-*.json)에서 로컬 시드 SQL(db/seed/V1xx__*.sql)을 만든다.
// 사용법: node doc/gen-venue-seed.mjs doc/venue-seed-concert-arena-44.json src/main/resources/db/seed/V105__insert_concert_arena_seed.sql
// 시드 SQL 은 손으로 고치지 않는다. 기준은 JSON 이고, 바꿀 것이 있으면 JSON 을 고쳐서 다시 만든다.
// 좌석 번호는 JSON 의 from~to 를 그대로 펼친다. (1부터 다시 세지 않는다. 열 안의 칸 위치라서 모양이 번호로 정해진다)
import { readFileSync, writeFileSync } from 'node:fs';
import { basename } from 'node:path';

const [jsonPath, sqlPath] = process.argv.slice(2);
if (!jsonPath || !sqlPath) {
	console.error('사용법: node doc/gen-venue-seed.mjs <공연장 JSON> <만들 SQL 파일>');
	process.exit(1);
}

const venue = JSON.parse(readFileSync(jsonPath, 'utf8'));
const q = (text) => `'${String(text).replaceAll("'", "''")}'`;

// ---- 입력 검증: 틀린 JSON 으로 시드를 만들지 않는다 ----
const zoneNames = new Set();
let totalSeats = 0;
let rowCount = 0;
for (const zone of venue.zones) {
	if (zoneNames.has(zone.name)) throw new Error(`구역 이름이 겹친다: ${zone.name}`);
	zoneNames.add(zone.name);
	const labels = new Set();
	for (const row of zone.rows) {
		if (labels.has(row.label)) throw new Error(`${zone.name} 의 열 이름이 겹친다: ${row.label}`);
		labels.add(row.label);
		if (!Number.isInteger(row.from) || !Number.isInteger(row.to) || row.to < row.from) {
			throw new Error(`${zone.name} ${row.label} 의 번호 범위가 틀리다: ${row.from}~${row.to}`);
		}
		totalSeats += row.to - row.from + 1;
		rowCount++;
	}
}

// 번호 범위가 열마다 다른 구역(부채꼴·사다리꼴)의 예시를 주석에 적는다.
const shaped = venue.zones.find((z) => new Set(z.rows.map((r) => r.from)).size > 1);
const first = shaped?.rows[0];
const last = shaped?.rows.at(-1);
const shapedExample = shaped
	? `예: ${shaped.name} ${first.label} ${first.from}~${first.to}, ${last.label} ${last.from}~${last.to}`
	: '';
const approximate = venue.zones.filter((z) => (z.memo ?? '').includes('[근사]')).length;

const lines = [];
const out = (text = '') => lines.push(text);

out(`-- 공연장 시드(모든 환경, 시연용): ${venue.name}. 구역 ${venue.zones.length}개, 좌석 ${totalSeats.toLocaleString('en-US')}석, layout_key = ${venue.layoutKey}.`);
out(`-- 이 파일은 doc/${basename(jsonPath)} 에서 doc/gen-venue-seed.mjs 로 만들었다. 손으로 고치지 말고 JSON 을 고친 뒤 다시 만든다.`);
out('--');
out('-- 지켜야 할 것: 이 공연장은 프론트가 구역 모양을 직접 그려서 보여 준다. 아래를 어기면 도면이 깨진다.');
out('--   - 구역 이름과 floor 는 JSON 그대로다. 프론트 도면(layout_key)이 구역을 이름으로 찾기 때문에, 이름을 바꾸면 도면과 연결이 끊긴다.');
out('--   - 구역 순서(sort_order)는 JSON 의 zones 순서다.');
out('--   - 좌석 번호(seat_number)는 1부터 센 순번이 아니라 "열 안의 칸 위치"다. 삼각형·사다리꼴 구역은 열마다 번호 범위가 달라서');
out(`--     그 차이가 도면의 모양이 된다. (${shapedExample}) 번호를 1..n 으로 다시 매기거나 열 길이를 맞추면 모양이 깨진다.`);
out('--   - 열 이름(row_label)도 JSON 의 label 그대로다.');
out('--   - 구역 안의 좌석 sort_order 는 JSON 의 열 순서, 같은 열 안에서는 번호 순서다.');
out('--   - 열 하나가 아래 VALUES 한 줄이다. (V100 의 Floor석처럼 열 이름을 직접 지정해서 번호 범위를 펼친다)');
if (approximate > 0) {
	out(`-- JSON 의 memo 에 [근사] 가 붙은 구역(${approximate}개)은 사진을 보고 읽은 대략적인 모양이라 열 길이가 실제와 다를 수 있다.`);
}
out('-- 이 시드는 모든 환경(운영 포함)에 적용된다. 공연장 생성 API 가 아직 없어서 시드로 넣는다.');
out('-- 개발용 공연장(가상 아레나·소극장)은 앱 시드에서 빼고 테스트 전용 시드(src/test/resources/db/test-seed)로 옮겼다. 시드는 V100 번대를 쓴다.');
out();
out('INSERT INTO venue (name, address, region, total_seats, layout_key, status, created_at, updated_at) VALUES');
out(`    (${q(venue.name)}, ${q(venue.address)}, ${q(venue.region)}, 0, ${q(venue.layoutKey)}, 'ACTIVE', NOW(), NOW());`);
out();
out('-- 구역 (적은 순서가 화면에 보이는 순서 sort_order 가 된다)');
out('INSERT INTO venue_zone (venue_id, name, floor, sort_order)');
out('SELECT v.id, z.name, z.floor, z.sort_order');
out('FROM venue v,');
out('     (VALUES');
out(venue.zones.map((z, i) => `        (${i + 1}, ${q(z.name)}, ${q(z.floor)})`).join(',\n'));
out('     ) AS z(sort_order, name, floor)');
out(`WHERE v.name = ${q(venue.name)};`);
out();
out('-- 좌석. 한 줄이 열 하나다: (순서, 구역, 열 시작, 열 끝, 열 이름 뒤에 붙일 글자, 열 이름 직접 지정, 번호 시작, 번호 끝)');
out('-- 열 이름을 직접 지정하므로 열 시작·끝·글자는 쓰지 않는다. (첫 줄의 NULL 은 PostgreSQL 이 열 종류를 알도록 적은 것이다)');
out('INSERT INTO venue_seat (venue_id, zone_id, row_label, seat_number, sort_order)');
out('SELECT v.id,');
out('       z.id,');
out('       COALESCE(d.row_name, rn || d.suffix),');
out('       sn::TEXT,');
out('       ROW_NUMBER() OVER (PARTITION BY z.id ORDER BY d.ord, rn, sn)');
out('FROM venue v');
out('JOIN venue_zone z ON z.venue_id = v.id');
out('JOIN (VALUES');
let ord = 0;
const valueLines = [];
for (const zone of venue.zones) {
	for (const row of zone.rows) {
		ord++;
		const cast = ord === 1 ? 'NULL::INTEGER, NULL::INTEGER, NULL::TEXT' : 'NULL, NULL, NULL';
		valueLines.push(`        (${ord}, ${q(zone.name)}, ${cast}, ${q(row.label)}, ${row.from}, ${row.to})`);
	}
}
out(valueLines.join(',\n'));
out('     ) AS d(ord, zone_name, r1, r2, suffix, row_name, n1, n2) ON d.zone_name = z.name');
out('CROSS JOIN LATERAL generate_series(COALESCE(d.r1, 1), COALESCE(d.r2, 1)) AS g1(rn)');
out('CROSS JOIN LATERAL generate_series(d.n1, d.n2) AS g2(sn)');
out(`WHERE v.name = ${q(venue.name)};`);
out();
out('-- 총 좌석 수는 만든 좌석을 세서 넣는다. 이 공연장 것만 갱신해서 다른 공연장의 값을 건드리지 않는다.');
out('UPDATE venue v');
out('SET total_seats = (SELECT COUNT(*) FROM venue_seat s WHERE s.venue_id = v.id)');
out(`WHERE v.name = ${q(venue.name)};`);
out();

writeFileSync(sqlPath, lines.join('\n'), 'utf8');
console.log(`만들었다: ${sqlPath} (구역 ${venue.zones.length}개, 열 ${rowCount}개, 좌석 ${totalSeats}석)`);
