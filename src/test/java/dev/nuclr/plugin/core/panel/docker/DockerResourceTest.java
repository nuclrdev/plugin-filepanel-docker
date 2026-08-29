/*

  Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.

*/
package dev.nuclr.plugin.core.panel.docker;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

class DockerResourceTest {

	@Test
	void rootHasExactlyTheThreeRequiredVirtualDirectories() {
		DockerFilePanelPlugin plugin = new DockerFilePanelPlugin();
		var listing = plugin.openResource(DockerResource.root(), null);
		assertEquals(List.of("Containers", "Images", "Volumes"),
				listing.getEntries().stream().map(entry -> entry.getName()).toList());
		assertTrue(listing.getEntries().stream().allMatch(entry -> entry.isFolder() && entry.getPath() == null));
		plugin.unload();
	}

	@Test
	void containerRetainsStableIdSeparatelyFromItsDisplayName() {
		DockerContainer model = new DockerContainer("abcdef0123456789", "friendly", "nginx:latest",
				"running", "Up 2 minutes", "today", "80/tcp", "team=one");
		DockerResource resource = DockerResource.container(model, null);
		assertEquals("friendly", resource.getName());
		assertEquals("abcdef0123456789", DockerResource.resourceId(resource));
		assertEquals("abcdef012345", resource.getMetadata("Container ID", ""));
		assertTrue(resource.isFolder());
	}

	@Test
	void temporaryContainerIdentificationUsesLabelNotName() {
		DockerContainer merelyNamed = new DockerContainer("1", "nuclr-image-browser-fake", "x", "created",
				"", "", "", "owner=user");
		DockerContainer labelled = new DockerContainer("2", "anything", "x", "created", "", "", "",
				DockerService.TEMPORARY_LABEL + "=true," + DockerService.PURPOSE_LABEL + "=image-browser");
		assertFalse(merelyNamed.temporary());
		assertTrue(labelled.temporary());
	}
}
