# Deploying Image Sorter on TrueNAS SCALE

This packages the whole app — Python, PyTorch (CPU), the YOLO model weights, and
both web UIs — into a single Docker image. Nothing needs to be installed on the
NAS except Docker, which TrueNAS SCALE already has.

The image runs two services in one container:

| Port | Service     | You open it at        |
|------|-------------|-----------------------|
| 7000 | Launcher UI | `http://<nas-ip>:7000` |
| 8080 | Sorter UI   | opened via the launcher's **Open UI** button |

Both bind `0.0.0.0` inside the container so they are reachable from your PC.

---

## 1. Get the source onto the NAS

Copy the whole repository to a dataset, e.g. `/mnt/YOUR_POOL/apps/image-sorter`.
Use an SMB share, `git clone`, or `scp`. You need the source, not `node_modules`
or `sorted/` (the `.dockerignore` already keeps those out of the image).

## 2. Prepare your config files

The launcher discovers users from files named
`config_<user>_<groupby|similarity>.yaml` in the `configs/` folder.

**The paths inside the configs must be container paths, not Windows/UNC paths.**
Mount your photo dataset at `/data` (see step 3) and point the configs there.
Example `configs/config_daniel_groupby.yaml`:

```yaml
mode: GroupByTags
web_ui: true
source_folder: /data/daniel/incoming      # was \\NAS\...\daniel
recursive: true
copy_instead_of_move: false

tag_groups:
  - name: Family
    tags: [person]
    destination: /data/daniel/sorted/family
    group_by_year: true
    group_by_month: true

unclassified:
  enabled: true
  folder_name: others
  destination: /data/daniel/sorted
```

## 3. Point the volumes at your dataset

Edit `docker-compose.yml` and change the data volume host path to your pool:

```yaml
    volumes:
      - ./configs:/configs:ro
      - /mnt/YOUR_POOL/photos:/data      # <-- your TrueNAS dataset here
```

Everything your configs read or write must live under that mounted path so it
maps to `/data/...` inside the container.

## 4. Build and start

From the project directory on the NAS (TrueNAS SCALE ships the `docker` CLI;
open a shell via **System Settings → Shell** or SSH):

```sh
cd /mnt/YOUR_POOL/apps/image-sorter
docker compose up -d --build
```

The first build downloads PyTorch and dependencies and builds the frontends —
expect several minutes. Subsequent starts are instant.

Check it came up:

```sh
docker compose logs -f
```

## 5. Use it

1. Browse to `http://<nas-ip>:7000`.
2. Pick a profile and click **Start GroupByTags** / **Start Similarity Search**.
3. Click **Open UI →** to open the sorter at `http://<nas-ip>:8080` and sort.
4. When the scan completes, **Back to launcher** returns you to port 7000.

Only one sorting job runs at a time.

---

## Updating after code changes

```sh
docker compose up -d --build
```

## Notes / troubleshooting

- **CPU only.** The image installs CPU PyTorch; it does not use the GPU. Tagging
  large libraries is slower than on a GPU machine but works unattended.
- **Model weights** (`yolo11s.pt`) are baked into the image, so the first run
  works without internet.
- **Adding TrueNAS Apps UI instead of CLI:** you can also register this under
  **Apps → Discover → Custom App** using the same image and the two port +
  volume mappings above. The compose route is simpler for a single host.
- If port 7000 or 8080 is already used on the NAS, remap the host side in
  `docker-compose.yml` (e.g. `"17000:7000"`). The launcher's link to the sorter
  assumes the sorter is reachable on host port **8080**, so if you remap 8080
  also update the launcher link expectation accordingly.
